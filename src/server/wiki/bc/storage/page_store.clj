(ns wiki.bc.storage.page-store
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [wiki.bc.cards.cards :refer [find-card-by-hash]]
   [wiki.bc.cards.parsing :as parsing]
   [wiki.bc.storage.page-storage :as page-storage])
  (:import (java.nio.file Files Paths)))

;; Data structures / types

;; page-path and system-path are Java nio Paths
;; git-repo? is boolean

;; Path helpers -- the nio types stay private to this namespace; the
;; IPageStore surface deals only in names and content strings.

(defn- path->pagename [path]
  (-> path .getFileName .toString (string/split #"\.") first))

(defn- page-name->path [page-path page-name]
  (.resolve page-path (str page-name ".md")))

(defn- system-name->path [system-path name]
  (.resolve system-path name))

(defn- media-dir-path [page-path]
  (.resolve page-path "media"))

(deftype PageStore [page-path system-path git-repo?]
  page-storage/IPageStore

  (as-map [_this]
    {:page-path   (str page-path)
     :system-path (str system-path)
     :git-repo?   git-repo?})

  (page-names [_this]
    (with-open [stream (Files/newDirectoryStream page-path "*.md")]
      (->> stream
           (mapv path->pagename)
           sort
           vec)))

  (page-exists? [_this page-name]
    (-> (page-name->path page-path page-name) .toFile .exists))

  (last-modified [_this page-name]
    (-> (page-name->path page-path page-name) .toFile .lastModified (#(java.util.Date. %))))

  (load-page [_this page-name]
    (-> (page-name->path page-path page-name) .toFile slurp))

  (get-page-as-card-maps [this page-name]
    (->> page-name
         (.load-page this)
         (parsing/raw-text->card-maps)))

  (get-card [this page-name hash-or-id]
    (-> (.get-page-as-card-maps this page-name)
        (find-card-by-hash hash-or-id)))

  (get-cards-from-page [this page-name hashes-or-ids]
    (->> hashes-or-ids
         (map #(.get-card this page-name %))
         (remove nil?)))

  (write-page! [_this page-name data]
    (spit (str (page-name->path page-path page-name)) data))

  (read-system-file [_this name]
    (-> (system-name->path system-path name) .toFile slurp))

  (write-system-file! [_this name data]
    (spit (str (system-name->path system-path name)) data))

  (read-recent-changes [this]
    (.read-system-file this "recentchanges"))

  (write-recent-changes! [this recent-changes]
    (.write-system-file! this "recentchanges" recent-changes))

  (similar-page-names [this page-name]
    (let [target (string/lower-case page-name)]
      (filter #(= (string/lower-case %) target) (.page-names this))))

  (media-list [_this]
    (let [media-dir (media-dir-path page-path)]
      (if (-> media-dir .toFile .isDirectory)
        (with-open [stream (Files/newDirectoryStream media-dir "*.*")]
          (->> stream
               (mapv #(str (.getFileName %)))
               sort
               vec))
        [])))

  (load-media-file [_this file-name]
    (io/file (str (media-dir-path page-path)) file-name))

  (report [_this]
    (str "Page Directory:  \t" (str page-path) "\n"
         "System Directory:\t" (str system-path) "\n"
         "Within Git Repo?:\t" (str git-repo?) "\n")))

;; Constructing

;; note - used externally
(defn make-page-store [page-dir-as-string]
  (let [page-dir-path (-> (Paths/get page-dir-as-string (make-array String 0))
                          (.toAbsolutePath)
                          (.normalize))
        system-dir-path (-> (Paths/get page-dir-as-string (into-array String ["system"]))
                            (.toAbsolutePath)
                            (.normalize))
        ;; note -- only verifies page-dir-path is a git root
        ;; todo -- check if within a git repo
        git-path (.resolve page-dir-path ".git")
        git-repo? (-> git-path .toFile .exists)
        page-store (->PageStore page-dir-path system-dir-path git-repo?)]

    (assert (-> page-dir-path .toFile .exists)
            (str "Given page-store directory " page-dir-as-string " does not exist."))
    (assert (-> page-dir-path .toFile .isDirectory)
            (str "page-store " page-dir-as-string " is not a directory."))
    (assert (-> system-dir-path .toFile .exists)
            (str "There is no system directory. Please make a directory called 'system' under the page directory "
                 page-dir-as-string))
    (assert (-> system-dir-path .toFile .isDirectory)
            (str "There is a file called 'system' under " page-dir-as-string
                 " but it is not a directory. Please remove that file and create a directory with that name"))
    page-store))

;; RecentChanges
;; We store recent-changes in a system file called "recentchanges".

(defn update-recent-changes! [page-store page-name]
  (let [rcc (.read-recent-changes page-store)
        filter-step (fn [xs] (filter #(not (string/includes? % (str "[[" page-name "]]"))) xs))
        curlist (-> rcc string/split-lines filter-step)
        newlist (cons
                 (str "* [[" page-name "]] (" (.toString (java.util.Date.)) ")")
                 curlist)]
    (.write-recent-changes! page-store (string/join "\n" (take 80 newlist)))))

;; Reading and writing against the server state's page-store

;; note - used externally
(defn read-page [server-state page-name]
  (-> server-state :page-store (.load-page page-name)))

;; note - used externally
(defn write-page-to-file! [server-state page-name body]
  (let [page-store (:page-store server-state)]
    (.write-page! page-store page-name body)
    (update-recent-changes! page-store page-name)))

;; region Search

;; Text Search
;; note - used externally
(defn text-search [server-state page-names pattern]
  (let [contains-pattern? (fn [page-name]
                            (let [text (read-page server-state page-name)]
                              (not (nil? (re-find pattern text)))))
        res (filter contains-pattern? page-names)]
    res))

;; Name Search - finds names containing substring
;; note - used externally
(defn name-search [page-names pattern]
  (filter #(not (nil? (re-find pattern %))) page-names))

;; endregion
