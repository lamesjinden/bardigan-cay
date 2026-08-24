(ns wiki.bc.storage.page-storage)

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
  (read-system-file [ps name])
  (write-system-file! [ps name data])
  (read-recent-changes [ps])
  (write-recent-changes! [ps new-rc])
  (similar-page-names [ps page-name])
  (media-list [ps])
  (load-media-file [ps file-name])
  (report [ps]))
