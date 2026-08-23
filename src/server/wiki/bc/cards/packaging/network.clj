(ns wiki.bc.cards.packaging.network
  (:require [hiccup.core :refer [html]]
            [wiki.bc.cards.parsing :as parsing]
            [wiki.bc.network :refer [network->svg]]
            [wiki.bc.util :as util]))

(defn package [id card-map render-context]
  (let [source-data (:source_data card-map)
        data        (parsing/card-map->card-data card-map)
        svg         (-> data
                        (network->svg)
                        (html))]
    (util/package-card id :network :markdown source-data svg render-context)))