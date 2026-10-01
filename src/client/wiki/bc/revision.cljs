(ns wiki.bc.revision
  "Client-side revision domain: the read-only snapshot a page may be
   viewed at (the [:revision] app-db value, nil for the live page) and
   the per-page revision list under [:revisions], whose entries arrive
   with the page itself. A snapshot shows either the rendered page or,
   with [:revisions :source?] on, its source text as committed."
  (:require [wiki.bc.navigation :as nav]))

(defn snapshot?
  "Whether the loaded page is a read-only snapshot at a git revision."
  [db]
  (some? (:revision @db)))

;; revisions are string-keyed maps, as served
(defn ->sha [revision] (get revision "sha"))
(defn ->short-sha [revision] (get revision "short_sha"))
(defn ->author [revision] (get revision "author"))
(defn ->date [revision] (get revision "date"))
(defn ->message [revision] (get revision "message"))
(defn ->head? [revision] (boolean (get revision "head?")))
;; the name the page had at the revision; nil when it was not a page then
(defn ->page-name [revision] (get revision "page_name"))

(defn former-name
  "The name the page had at revision when that differs from page-name,
   what it is called now -- the page has been renamed since; nil
   otherwise."
  [revision page-name]
  (let [name-at (->page-name revision)]
    (when (and (some? name-at)
               (not= name-at page-name))
      name-at)))

(defn position
  "Where revision sits in entries, a page's revisions newest first:
   :number counts from the oldest (1) up to :total, and :older / :newer
   are the neighbouring entries (nil at either end). nil when revision
   is not one of entries -- a commit that did not touch the page."
  [entries revision]
  (let [sha (->sha revision)
        index (first (keep-indexed (fn [i entry]
                                     (when (= sha (->sha entry))
                                       i))
                                   entries))]
    (when index
      (let [total (count entries)]
        {:number (- total index)
         :total  total
         :older  (get entries (inc index))
         :newer  (get entries (dec index))}))))

(defn <view-revision!
  "Navigates to the current page as committed at sha."
  [db sha]
  (nav/<navigate-revision! db (:current-page @db) sha))

(defn <exit-snapshot!
  "Navigates back to the live current page."
  [db]
  (nav/<navigate! db (:current-page @db)))

(defn source?
  "Whether the loaded page is a snapshot showing its source text."
  [db]
  (and (snapshot? db)
       (boolean (get-in @db [:revisions :source?]))))

(defn toggle-source! [db]
  (swap! db update-in [:revisions :source?] not))

(defn close-revision-list! [db]
  (swap! db assoc-in [:revisions :open?] false))

(defn toggle-revision-list! [db]
  (swap! db update-in [:revisions :open?] not))
