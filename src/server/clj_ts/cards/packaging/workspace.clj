(ns clj-ts.cards.packaging.workspace
  (:require [clj-ts.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :workspace :workspace source-data source-data render-context))