(ns clj-ts.cards.packaging.network
  (:require [hiccup.core :refer [html]]
            [clj-ts.common :as common]
            [clj-ts.network :refer [network->svg]]
            [clj-ts.util :as util]))

(defn package [id card-map render-context]
  (let [source-data (:source_data card-map)
        data        (common/card-map->card-data card-map)
        svg         (-> data
                        (network->svg)
                        (html))]
    (util/package-card id :network :markdown source-data svg render-context)))