(ns wiki.bc.revision
  "Client-side revision domain: the read-only snapshot a page may be
   viewed at (the [:revision] app-db value, nil for the live page) and
   the per-page revision list under [:revisions], whose entries arrive
   with the page itself."
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

(defn <view-revision!
  "Navigates to the current page as committed at sha."
  [db sha]
  (nav/<navigate-revision! db (:current-page @db) sha))

(defn <exit-snapshot!
  "Navigates back to the live current page."
  [db]
  (nav/<navigate! db (:current-page @db)))

(defn close-revision-list! [db]
  (swap! db assoc-in [:revisions :open?] false))

(defn toggle-revision-list! [db]
  (swap! db update-in [:revisions :open?] not))
