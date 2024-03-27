(ns clj-ts.cards.packaging.graph
  (:require [clj-ts.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :graph :graph source-data source-data render-context))