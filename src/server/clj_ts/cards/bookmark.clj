(ns clj-ts.cards.bookmark
  (:require [clj-ts.cards.parsing :as parsing]
            [clj-ts.util :as util]))

(defn bookmark-card [card-map]
  (let [data (parsing/card-map->card-data card-map)
        {:keys [url timestamp title]} data
        url' (util/nonblank url "url missing")
        title' (util/nonblank title url)
        timestamp' (if timestamp
                     (format " \n\n_saved: %s_" timestamp)
                     "")]
    (format "\n[%s](%s)%s\n" title' url' timestamp')))
