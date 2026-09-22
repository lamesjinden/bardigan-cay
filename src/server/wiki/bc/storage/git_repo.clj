(ns wiki.bc.storage.git-repo
  "Read-only access to the git repository enclosing the wiki directory,
  through JGit, pure JVM.

  A repo is {:repository     org.eclipse.jgit.lib.Repository
             :page-prefix    \"path/within/work/tree/\"
             :revision-cache (atom ...)}
  where page-prefix locates the wiki directory relative to the work
  tree (\"\" when the wiki directory is the repository root, otherwise
  slash-terminated). Every path handed to git is prefix + name, so the
  wiki may sit anywhere inside the repository, not only at its root.

  Nothing here writes to the repository: history is listed and blobs
  are read at a given commit. The Repository is safe to share across
  request threads; each call opens its own RevWalk/TreeWalk.

  Listing a page's revisions walks the repository's entire history (the
  cost is proportional to the number of commits, not to the page), so
  the lists are cached per page, keyed by HEAD: a page's committed
  history can only change when HEAD moves, so the cache is exact and
  never stale. See page-revisions."
  (:require [clojure.string :as string])
  (:import (java.nio.file Path Paths)
           (java.util Date)
           (org.eclipse.jgit.diff DiffConfig)
           (org.eclipse.jgit.lib Constants ObjectId Repository)
           (org.eclipse.jgit.revwalk FollowFilter RevCommit RevTree RevWalk)
           (org.eclipse.jgit.storage.file FileRepositoryBuilder)
           (org.eclipse.jgit.treewalk TreeWalk)
           (org.eclipse.jgit.treewalk.filter PathFilter)))

;; Paths

(def ^:private page-extension ".md")

(defn- page-path [page-prefix page-name]
  (str page-prefix page-name page-extension))

(defn- system-path [page-prefix name]
  (str page-prefix "system/" name))

(defn- prefix->tree-path
  "A slash-terminated prefix as the path git addresses the directory by
  (no trailing slash), or nil for the work tree root."
  [page-prefix]
  (when-not (string/blank? page-prefix)
    (subs page-prefix 0 (dec (count page-prefix)))))

(defn- relative-prefix
  "page-dir relative to the repository's work tree, slash-terminated;
  \"\" when they are the same directory."
  [^Repository repository ^Path page-dir]
  (let [work-tree (-> repository .getWorkTree .toPath .toAbsolutePath .normalize)
        relative (.relativize work-tree page-dir)
        segments (map str relative)]
    (if (string/blank? (str relative))
      ""
      (str (string/join "/" segments) "/"))))

;; Opening

(defn open-repo
  "The repository whose work tree contains page-dir (a directory path
  string), searching upward from page-dir like git itself does -- so a
  wiki nested anywhere inside a checkout is found, and .git files
  (worktrees) are honoured. nil when page-dir is not inside a work tree."
  [page-dir-as-string]
  (let [page-dir (-> (Paths/get page-dir-as-string (make-array String 0))
                     (.toAbsolutePath)
                     (.normalize))
        builder (-> (FileRepositoryBuilder.)
                    (.findGitDir (.toFile page-dir)))]
    (when (.getGitDir builder)
      (let [repository (.build builder)]
        (if (.isBare repository)
          (do
            (.close repository)
            nil)
          {:repository     repository
           :page-prefix    (relative-prefix repository page-dir)
           :revision-cache (atom {:head nil :pages {}})})))))

(defn close!
  "Releases the repository's file handles and caches."
  [{:keys [^Repository repository]}]
  (.close repository))

(defn report
  "One-line description for the startup log."
  [repo]
  (if-let [{:keys [^Repository repository page-prefix]} repo]
    (str "true (work tree: " (.getWorkTree repository)
         ", pages at: " (if (string/blank? page-prefix) "<root>" page-prefix) ")")
    "false"))

;; Commits

(defn- commit-tree ^RevTree [^RevWalk walk sha]
  (.getTree (.parseCommit walk (ObjectId/fromString sha))))

(defn- commit->info
  "The client-facing description of a commit."
  [^RevCommit commit head-sha]
  (let [ident (.getAuthorIdent commit)
        sha (.getName commit)]
    {:sha       sha
     :short_sha (subs sha 0 7)
     :author    (.getName ident)
     :date      (str (.getWhenAsInstant ident))
     :message   (.getShortMessage commit)
     :head?     (= sha head-sha)}))

(defn- head-sha [^Repository repository]
  (some-> (.resolve repository Constants/HEAD)
          (.getName)))

(defn resolve-commit
  "The full sha of the commit named by rev (a sha, an abbreviated sha, a
  ref name), or nil when rev names nothing, is ambiguous, or is not a
  commit."
  [{:keys [^Repository repository]} rev]
  (when-not (string/blank? rev)
    (try
      (when-let [id (.resolve repository rev)]
        (with-open [walk (RevWalk. repository)]
          (.getName (.parseCommit walk id))))
      (catch Exception _
        nil))))

(defn commit-info
  "The description of the commit at (full) sha."
  [{:keys [^Repository repository]} sha]
  (with-open [walk (RevWalk. repository)]
    (commit->info (.parseCommit walk (ObjectId/fromString sha))
                  (head-sha repository))))

(defn commit-date
  "The author date of the commit at sha, as a java.util.Date."
  [{:keys [^Repository repository]} sha]
  (with-open [walk (RevWalk. repository)]
    (-> (.parseCommit walk (ObjectId/fromString sha))
        (.getAuthorIdent)
        (.getWhenAsInstant)
        (Date/from))))

(defn- walk-page-revisions
  "The history walk behind page-revisions: every commit from head that
  touched page-name's file, newest first, following renames (git log
  --follow). Visits the whole history, so callers cache the result."
  [^Repository repository page-prefix ^ObjectId head page-name]
  (with-open [walk (RevWalk. repository)]
    (let [diff-config (.get (.getConfig repository) DiffConfig/KEY)
          follow (FollowFilter/create (page-path page-prefix page-name) diff-config)
          head-sha (.getName head)]
      (.markStart walk (.parseCommit walk head))
      (.setTreeFilter walk follow)
      (mapv (fn [commit] (commit->info commit head-sha))
            (iterator-seq (.iterator walk))))))

(defn- cached-revisions
  "The cached list for page-name computed under head-sha, or nil."
  [cache head-sha page-name]
  (when (= head-sha (:head cache))
    (get (:pages cache) page-name)))

(defn- remember-revisions
  "cache with revisions recorded for page-name under head-sha; a cache
  built under a different HEAD is discarded wholesale, since any page's
  history may have changed."
  [cache head-sha page-name revisions]
  (if (= head-sha (:head cache))
    (assoc-in cache [:pages page-name] revisions)
    {:head  head-sha
     :pages {page-name revisions}}))

(defn page-revisions
  "The commits that touched page-name's file, newest first, following
  the file across renames (git log --follow). Empty for a page git has
  never seen, and for a repository with no commits yet.

  Cached per page under the current HEAD: the walk runs once per page
  per commit, and every later call under the same HEAD is a lookup.
  Two threads racing on a cold page both walk and both record the same
  list, which is harmless."
  [{:keys [^Repository repository page-prefix revision-cache]} page-name]
  (if-let [head (.resolve repository Constants/HEAD)]
    (let [head-sha (.getName head)]
      (or (cached-revisions @revision-cache head-sha page-name)
          (let [revisions (walk-page-revisions repository page-prefix head page-name)]
            (swap! revision-cache remember-revisions head-sha page-name revisions)
            revisions)))
    []))

;; Trees

(defn- read-blob
  "The UTF-8 content of path in tree, or nil when path is not a file
  there."
  [^Repository repository ^RevTree tree path]
  (when-let [tree-walk (TreeWalk/forPath repository ^String path tree)]
    (with-open [tree-walk tree-walk]
      (when-not (.isSubtree tree-walk)
        (String. (.getBytes (.open repository (.getObjectId tree-walk 0))) "UTF-8")))))

(defn- file-exists?
  [^Repository repository ^RevTree tree path]
  (when-let [tree-walk (TreeWalk/forPath repository ^String path tree)]
    (with-open [tree-walk tree-walk]
      (not (.isSubtree tree-walk)))))

(defn page-body
  "page-name's content at commit sha, or nil when the page did not exist
  there."
  [{:keys [^Repository repository page-prefix]} sha page-name]
  (with-open [walk (RevWalk. repository)]
    (read-blob repository (commit-tree walk sha) (page-path page-prefix page-name))))

(defn page-exists?
  [{:keys [^Repository repository page-prefix]} sha page-name]
  (with-open [walk (RevWalk. repository)]
    (boolean (file-exists? repository (commit-tree walk sha) (page-path page-prefix page-name)))))

(defn system-file
  "The content of the wiki's system/name file at commit sha, or nil."
  [{:keys [^Repository repository page-prefix]} sha name]
  (with-open [walk (RevWalk. repository)]
    (read-blob repository (commit-tree walk sha) (system-path page-prefix name))))

(defn- entry->page-name
  "The page name of a tree entry directly under page-prefix that is a
  page file; nil for anything else (nested paths, non-page files)."
  [page-prefix entry-path]
  (let [relative (subs entry-path (count page-prefix))]
    (when (and (string/ends-with? relative page-extension)
               (not (string/includes? relative "/")))
      (subs relative 0 (- (count relative) (count page-extension))))))

(defn page-names
  "The sorted names of the pages in the wiki directory at commit sha."
  [{:keys [^Repository repository page-prefix]} sha]
  (with-open [walk (RevWalk. repository)
              tree-walk (TreeWalk. repository)]
    (.addTree tree-walk (commit-tree walk sha))
    (.setRecursive tree-walk true)
    (when-let [tree-path (prefix->tree-path page-prefix)]
      (.setFilter tree-walk (PathFilter/create tree-path)))
    (loop [names (transient [])]
      (if (.next tree-walk)
        (recur (if-let [page-name (entry->page-name page-prefix (.getPathString tree-walk))]
                 (conj! names page-name)
                 names))
        (vec (sort (persistent! names)))))))
