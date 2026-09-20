(ns wiki.bc.test-fixtures
  "Shared fixtures for tests that need a wiki on disk: one throwaway-dir
  builder instead of a copy per namespace, and index builders whose LMDB
  envs and scratch directories are reliably closed."
  (:require [clojure.java.io :as io]
            [wiki.bc.storage.index :as index]
            [wiki.bc.storage.page-store :as pagestore]
            [wiki.bc.storage.reconcile :as reconcile])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.eclipse.jgit.api Git]))

(defn temp-wiki-dir
  "A throwaway wiki directory (java.io.File) holding the given
  {page-name source} pages, an empty system/recentchanges, and
  optionally {file-name content} media (content may be a String or a
  byte array)."
  ([pages]
   (temp-wiki-dir pages {}))
  ([pages media]
   (let [dir (-> (Files/createTempDirectory "bc-test-wiki" (make-array FileAttribute 0))
                 (.toFile))]
     (.mkdirs (io/file dir "system"))
     (spit (io/file dir "system" "recentchanges") "")
     (doseq [[page-name source] pages]
       (spit (io/file dir (str page-name ".md")) source))
     (when (seq media)
       (.mkdirs (io/file dir "media"))
       (doseq [[file-name content] media]
         (with-open [out (io/output-stream (io/file dir "media" file-name))]
           (io/copy content out))))
     dir)))

(defn await-search-sync!
  "Blocks until the index's reconcile process has drained every
  notification sent before this call; true on completion. Tests that
  assert engine state after a runtime mutation need this barrier --
  search is eventually consistent by contract, which is also why this
  helper lives in the test tree: production code must never wait on
  search, so the index namespace deliberately offers no way to."
  [{:keys [notify$]}]
  (reconcile/await! notify$ 10000))

(defn build-index
  "A built index over dir. The caller owns closing it (index/close! in a
  finally); tests that cannot thread a close conveniently should use
  tracked-index + close-tracked-indexes instead."
  [dir]
  (index/build! (index/open-index) (pagestore/make-page-store (str dir))))

(def ^:private tracked-indexes (atom []))

(defn tracked-index
  "build-index whose result is closed by the close-tracked-indexes
  fixture -- for tests that bury the index inside a snapshot or request
  pipeline and have no natural place to close it."
  [dir]
  (let [idx (build-index dir)]
    (swap! tracked-indexes conj idx)
    idx))

(defn close-tracked-indexes
  "clojure.test fixture (use-fixtures :each) closing every tracked-index
  built during the test, so no LMDB env or scratch directory outlives
  the test run."
  [f]
  (try
    (f)
    (finally
      (doseq [idx @tracked-indexes]
        (index/close! idx))
      (reset! tracked-indexes []))))

;; Git fixtures: a wiki nested inside a throwaway repository, driven
;; through JGit so the tests need no git binary either.

(defn temp-git-wiki
  "A throwaway repository whose work tree holds the given pages under
  site/wiki (nested, not at the root, so the prefix handling is always
  exercised). Returns {:root <File> :dir <File>} -- the repository root
  and the wiki directory -- with nothing committed yet."
  [pages]
  (let [root (-> (Files/createTempDirectory "bc-test-repo" (make-array FileAttribute 0))
                 (.toFile))
        dir (io/file root "site" "wiki")]
    (.mkdirs (io/file dir "system"))
    (spit (io/file dir "system" "recentchanges") "")
    (doseq [[page-name source] pages]
      (spit (io/file dir (str page-name ".md")) source))
    (with-open [git (-> (Git/init) (.setDirectory root) (.call))]
      git)
    {:root root
     :dir  dir}))

(defn write-page!
  "Writes (or overwrites) page-name's file in the wiki dir; nil source
  deletes it."
  [dir page-name source]
  (let [file (io/file dir (str page-name ".md"))]
    (if (nil? source)
      (.delete file)
      (spit file source))))

(defn commit-all!
  "Stages every change under root (additions, edits, deletions) and
  commits it; returns the new commit's full sha."
  [root message]
  (with-open [git (Git/open root)]
    (-> git (.add) (.addFilepattern ".") (.call))
    (-> git
        (.commit)
        (.setAll true)
        (.setMessage message)
        (.setAuthor "Tester" "tester@example.com")
        (.setCommitter "Tester" "tester@example.com")
        (.call)
        (.getName))))
