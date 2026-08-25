(ns wiki.bc.storage.indexed-page-store
  "IPageStore that reads from the Datalevin page index and writes through
  to the file-backed store.

  The markdown files stay the source of truth: every write goes to the
  file first, then re-indexes that page, so the index can never be the
  only holder of content. Reads (bodies, names, existence, mtimes, and
  parsed cards -- including lookup by hash or declared :card/id) come
  from the index; media, system files, and the store description stay
  with the file store, which is also where external tools (editors, git)
  see content -- an external edit is invisible here until the page is
  re-indexed."
  (:require [wiki.bc.storage.index :as index]
            [wiki.bc.storage.page-storage :as page-storage]))

(defn- page-missing! [page-name]
  (throw (ex-info (str "page " page-name " is not in the index")
                  {:page-name page-name})))

(deftype IndexedPageStore [page-index file-store]
  page-storage/IPageStore

  (as-map [_this]
    (page-storage/as-map file-store))

  (page-names [_this]
    (index/page-names page-index))

  (page-exists? [_this page-name]
    (index/page-exists? page-index page-name))

  (last-modified [_this page-name]
    (index/page-last-modified page-index page-name))

  (load-page [_this page-name]
    (or (index/page-body page-index page-name)
        (page-missing! page-name)))

  (get-page-as-card-maps [_this page-name]
    (if (index/page-exists? page-index page-name)
      (index/page-cards page-index page-name)
      (page-missing! page-name)))

  (get-card [_this page-name hash-or-id]
    (if-let [[card-map locator] (index/lookup-card page-index page-name hash-or-id)]
      (assoc card-map :tx/locator locator)
      (when-not (index/page-exists? page-index page-name)
        (page-missing! page-name))))

  (get-cards-from-page [this page-name hashes-or-ids]
    (page-storage/cards-from-page this page-name hashes-or-ids))

  (write-page! [_this page-name data]
    (page-storage/write-page! file-store page-name data)
    (index/index-page! page-index file-store page-name data))

  (write-page-delta! [_this page-name data delta]
    (page-storage/write-page! file-store page-name data)
    (index/index-card-delta! page-index file-store page-name data delta))

  (refresh-page! [_this page-name]
    (index/refresh-page! page-index file-store page-name))

  (read-system-file [_this name]
    (page-storage/read-system-file file-store name))

  (write-system-file! [_this name data]
    (page-storage/write-system-file! file-store name data))

  (read-recent-changes [_this]
    (page-storage/read-recent-changes file-store))

  (write-recent-changes! [_this recent-changes]
    (page-storage/write-recent-changes! file-store recent-changes))

  (similar-page-names [this page-name]
    (page-storage/similarly-named-pages this page-name))

  (media-list [_this]
    (page-storage/media-list file-store))

  (load-media-file [_this file-name]
    (page-storage/load-media-file file-store file-name))

  (report [_this]
    (str (page-storage/report file-store)
         "Page Index:      \t" (:dir page-index) "\n")))

(defn make-indexed-page-store [page-index file-store]
  (->IndexedPageStore page-index file-store))
