(ns wiki.bc.storage.git-page-store
  "IPageStore over the wiki as committed at one git revision.

  Pages, cards and system files are read from the commit's tree, so a
  page rendered through this store -- transclusions included -- shows the
  wiki as it was at that commit. Media stays with the live store: media
  files are served by name from disk and are not versioned per page.

  The store is read-only: every write throws. It holds no derived state,
  so it is cheap to build per request and needs no closing."
  (:require [wiki.bc.cards.cards :refer [find-card-by-hash]]
            [wiki.bc.cards.parsing :as parsing]
            [wiki.bc.storage.git-repo :as git-repo]
            [wiki.bc.storage.page-storage :as page-storage]))

(defn- page-missing! [page-name sha]
  (throw (ex-info (str "page " page-name " does not exist at revision " sha)
                  {:page-name page-name :revision sha})))

(defn- read-only! [sha]
  (throw (ex-info (str "the wiki at revision " sha " is read-only")
                  {:revision sha})))

(deftype GitPageStore [repo sha media-store]
  page-storage/IPageStore

  (as-map [_this]
    (assoc (page-storage/as-map media-store) :revision sha))

  (page-names [_this]
    (git-repo/page-names repo sha))

  (page-exists? [_this page-name]
    (git-repo/page-exists? repo sha page-name))

  (last-modified [_this _page-name]
    (git-repo/commit-date repo sha))

  (load-page [_this page-name]
    (or (git-repo/page-body repo sha page-name)
        (page-missing! page-name sha)))

  (get-page-as-card-maps [this page-name]
    (parsing/raw-text->card-maps (.load-page this page-name)))

  (get-card [this page-name hash-or-id]
    (-> (.get-page-as-card-maps this page-name)
        (find-card-by-hash hash-or-id)))

  (get-cards-from-page [this page-name hashes-or-ids]
    (page-storage/cards-from-page this page-name hashes-or-ids))

  (write-page! [_this _page-name _data]
    (read-only! sha))

  (write-page-delta! [_this _page-name _data _delta]
    (read-only! sha))

  (refresh-page! [_this _page-name]
    nil)

  ;; a system file absent from the commit reads as empty, matching what
  ;; the live store's readers expect of a fresh wiki
  (read-system-file [_this name]
    (or (git-repo/system-file repo sha name) ""))

  (write-system-file! [_this _name _data]
    (read-only! sha))

  (read-recent-changes [this]
    (.read-system-file this "recentchanges"))

  (write-recent-changes! [_this _recent-changes]
    (read-only! sha))

  (similar-page-names [this page-name]
    (page-storage/similarly-named-pages this page-name))

  (media-list [_this]
    (page-storage/media-list media-store))

  (load-media-file [_this file-name]
    (page-storage/load-media-file media-store file-name))

  (report [_this]
    (str "Git Revision:\t" sha "\n")))

(defn make-git-page-store
  "The wiki at (full) commit sha, with media served by media-store."
  [repo sha media-store]
  (->GitPageStore repo sha media-store))
