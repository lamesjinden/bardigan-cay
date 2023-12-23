(ns clj-ts.storage.page_store
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.core.memoize :refer [memo memo-clear!]]
    [clj-ts.cards.card-data :as card-data]
    [clj-ts.common :refer [find-card-by-hash]]
    [clj-ts.storage.page-storage :as page-storage])
  (:import (java.nio.file Files Path Paths)))

;; Data structures / types

;; page-path, system-path, export-path are Java nio Paths
;; git-repo? is boolean

(deftype PageStore [page-path system-path export-path git-repo?]
  page-storage/IPageStore

  (as-map [_this]
    {:page-path   page-path
     :system-path system-path
     :export-path export-path
     :git-repo?   git-repo?})

  (page-name->path [_this page-name]
    (.resolve page-path (str page-name ".md")))

  (name->system-path [_this name]
    (.resolve system-path name))

  (page-exists? [this page-name]
    (-> (.page-name->path this page-name) .toFile .exists))

  (system-file-exists? [this name]
    (-> (.name->system-path this name) .toFile .exists))

  (last-modified [this page-name]
    (-> (.page-name->path this page-name) .toFile .lastModified (#(java.util.Date. %))))

  (load-page [this page]
    (if (instance? Path page)
      (-> page .toFile slurp)
      (-> page (#(.page-name->path this %)) .toFile slurp)))

  (get-page-as-card-maps [this page-name]
    (->> page-name
         (.page-name->path this)
         (.load-page this)
         (card-data/raw-text->card-maps)))

  (get-card [this page-name hash]
    (-> (.get-page-as-card-maps this page-name)
        (find-card-by-hash hash)))

  (get-cards-from-page [this page-name card-hashes]
    (remove nil? (map #(.get-card this page-name %) card-hashes)))

  (write-page! [this page data]
    (if (instance? Path page)
      (spit (.toString page) data)
      (let [x (-> page (#(.page-name->path this %)))]
        (spit (.toString x) data))))

  (read-system-file [this name]
    (if (instance? Path name)
      (-> name .toFile slurp)
      (-> name (#(.name->system-path this %)) .toFile slurp)))

  (write-system-file! [this name data]
    (if (instance? Path name)
      (spit (.toString name) data)
      (let [x (-> name (#(.name->system-path this %)))]
        (spit (.toString x) data))))

  (report [_this]
    (str "Page Directory:  \t" (str page-path) "\n"
         "System Directory:\t" (str system-path) "\n"
         "Export Directory:\t" (str export-path) "\n"
         "Within Git Repo?:\t" (str git-repo?) "\n"))

  (similar-page-names [this page-name]
    (let [all-pages (.pages-as-new-directory-stream this)
          all-names (map #(-> (.getFileName %)
                              .toString
                              (string/split #"\.")
                              butlast
                              last)
                         all-pages)]
      (filter #(= (string/lower-case %) (string/lower-case page-name)) all-names)))

  (pages-as-new-directory-stream [_this]
    (Files/newDirectoryStream page-path "*.md"))

  (media-files-as-new-directory-stream [_this]
    (let [media-path (.resolve page-path "media")]
      (Files/newDirectoryStream media-path "*.*")))

  (read-recent-changes [this]
    (.read-system-file this "recentchanges"))

  (recent-changes-as-page-list [page-store]
    (->> (clojure.string/split-lines (.read-recent-changes page-store))
         (map (fn [line] (first (re-seq #"\[\[(.+?)\]\]" line))))
         (map second)))

  (write-recent-changes! [this recent-changes]
    (.write-system-file! this "recentchanges" recent-changes))

  (load-media-file [_this file-name]
    (let [media-dir (.toString (.resolve page-path "media"))]
      (io/file media-dir file-name)))

  (media-list [this]
    (let [files (.media-files-as-new-directory-stream this)]
      (map #(.getFileName %) files))))

;; Constructing

;; note - used externally
(defn make-page-store [page-dir-as-string export-dir-as-string]
  (let [page-dir-path (-> (Paths/get page-dir-as-string (make-array String 0))
                          (.toAbsolutePath)
                          (.normalize))
        system-dir-path (-> (Paths/get page-dir-as-string (into-array String ["system"]))
                            (.toAbsolutePath)
                            (.normalize))
        export-dir-path (-> (Paths/get export-dir-as-string (make-array String 0))
                            (.toAbsolutePath)
                            (.normalize))
        ;; note -- only verifies page-dir-path is a git root
        ;; todo -- check if within a git repo
        git-path (.resolve page-dir-path ".git")
        git-repo? (-> git-path .toFile .exists)
        page-store (->PageStore page-dir-path system-dir-path export-dir-path git-repo?)]

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
    (assert (-> export-dir-path .toFile .exists)
            (str "Given export-dir-path " export-dir-as-string " does not exist."))
    (assert (-> export-dir-path .toFile .isDirectory)
            (str "export-path " export-dir-as-string " is not a directory."))
    page-store))

;; Basic functions

;; note - used externally (logic)
(defn path->pagename [path]
  (-> path .getFileName .toString (string/split #"\.") first))

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

;; API for writing a file

(defn m-read-page [page-store page-name]
  (.load-page page-store page-name))

(def memoized-read-page (memo m-read-page))

;; note - used externally
(defn read-page [server-state page-name]
  (let [page-store (:page-store server-state)]
    (memoized-read-page page-store page-name)))

;; note - used externally
(defn write-page-to-file! [server-state page-name body]
  (let [page-store (.page-store server-state)]
    (.write-page! page-store page-name body)
    (update-recent-changes! page-store page-name)
    (memo-clear! memoized-read-page [page-store page-name])))

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
