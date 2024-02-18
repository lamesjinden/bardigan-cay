(ns clj-ts.cards.packaging.bookmark
  (:require [clj-ts.cards.bookmark :as bookmark]
            [clj-ts.util :as util]))

(defn package [id card-map render-context]
  (let [source-data (:source_data card-map)
        server-prepared-data (bookmark/bookmark-card card-map)]
    (util/package-card id :bookmark :markdown source-data server-prepared-data render-context)))