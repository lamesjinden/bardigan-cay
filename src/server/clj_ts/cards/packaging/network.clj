(ns clj-ts.cards.packaging.network
  (:require [clojure.edn :as edn]
            [hiccup.core :refer [html]]
            [clj-ts.util :as util]
            [clj-ts.network :refer [network->svg]]))

(defn package [id source-data render-context]
  (let [svg (-> source-data
                (edn/read-string)
                (network->svg)
                (html))]
    (util/package-card id :network :markdown source-data svg render-context)))