(ns wiki.bc.cards.packaging.filelink
  (:require [wiki.bc.cards.filelink :as filelink]
            [wiki.bc.util :as util]))

(defn package [id card-map render-context]
  (let [source-data (:source_data card-map)
        server-prepared-data (filelink/render-html card-map)]
    (util/package-card id :filelink :html source-data server-prepared-data render-context)))