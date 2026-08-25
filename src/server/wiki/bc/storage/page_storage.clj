(ns wiki.bc.storage.page-storage
  (:require [clojure.string :as string]))

;; The page-store contract speaks in page/file NAMES and content strings;
;; no java.nio types cross this boundary. The one deliberate exception is
;; load-media-file, which returns a java.io.File because media is served
;; to Ring as a file response.
(defprotocol IPageStore
  (as-map [ps])
  (page-names [ps])
  (page-exists? [ps page-name])
  (last-modified [ps page-name])
  ;; note - named load-page (not read-page) to avoid collision with pagestore/read-page
  (load-page [ps page-name])
  (get-page-as-card-maps [ps page-name])
  (get-card [ps page-name card-hash])
  (get-cards-from-page [ps page-name card-hashes])
  (write-page! [ps page-name data])
  ;; write-page! for card-level edits: data is still the full new page
  ;; body (the file write is always whole-page), delta describes the
  ;; card-scoped change so an index-backed store can update just the
  ;; affected card instead of re-parsing the page. See
  ;; wiki.bc.storage.index/index-card-delta! for the delta shapes.
  (write-page-delta! [ps page-name data delta])
  ;; folds external file changes (hand edit, git pull, create, delete)
  ;; into any derived state the store keeps; a no-op for stores that read
  ;; the files directly. Mutating callers that read page content in order
  ;; to write it back MUST call this first.
  (refresh-page! [ps page-name])
  (read-system-file [ps name])
  (write-system-file! [ps name data])
  (read-recent-changes [ps])
  (write-recent-changes! [ps new-rc])
  (similar-page-names [ps page-name])
  (media-list [ps])
  (load-media-file [ps file-name])
  (report [ps]))

;; Shared method bodies: pure protocol-level compositions that would
;; otherwise be copy-pasted into every IPageStore implementation.

(defn cards-from-page
  "The store's get-card per hash-or-id, in the given order, misses
  dropped."
  [store page-name hashes-or-ids]
  (->> hashes-or-ids
       (map (fn [hash-or-id] (get-card store page-name hash-or-id)))
       (remove nil?)))

(defn similarly-named-pages
  "The store's page names equal to page-name ignoring case."
  [store page-name]
  (let [target (string/lower-case page-name)]
    (filter (fn [n] (= (string/lower-case n) target))
            (page-names store))))
