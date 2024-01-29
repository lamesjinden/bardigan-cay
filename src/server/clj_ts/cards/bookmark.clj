(ns clj-ts.cards.bookmark
  (:require [clojure.edn :as edn]
            [clj-ts.util :as util]))

(defn bookmark-card [data]
  ;; todo/note - change to account for non-desctructive card parsing
  (let [{:keys [url timestamp title]} (edn/read-string data)
        url (util/nonblank url "url missing")
        title (util/nonblank title url)
        timestamp' (if timestamp
                     (format " \n\n_saved: %s_" timestamp)
                     "")]
    (format "\n[%s](%s)%s\n" title url timestamp')))
