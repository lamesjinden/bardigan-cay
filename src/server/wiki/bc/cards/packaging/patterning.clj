(ns wiki.bc.cards.packaging.patterning
  (:require [wiki.bc.cards.patterning :as patterning]
            [wiki.bc.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :patterning :html source-data (patterning/one-pattern source-data) render-context))