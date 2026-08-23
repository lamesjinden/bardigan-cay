(ns wiki.bc.cards.packaging.workspace
  (:require [wiki.bc.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :workspace :workspace source-data source-data render-context))