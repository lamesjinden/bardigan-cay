(ns wiki.bc.cards.packaging.graph
  (:require [wiki.bc.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :graph :graph source-data source-data render-context))