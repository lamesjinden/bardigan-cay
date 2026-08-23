(ns wiki.bc.cards.packaging.raw
  (:require [wiki.bc.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :raw :raw source-data source-data render-context))