(ns clj-ts.cards.network
  (:require [clojure.edn :as edn]
            [hiccup.core :refer [html]]
            [clj-ts.cards.parsing :as parsing]
            [clj-ts.network :refer [network->svg]]))

(defn network-card [i data render-context]
  (let [svg (html (network->svg (edn/read-string data)))]
    (parsing/package-card
      i :network :markdown data
      svg render-context)))