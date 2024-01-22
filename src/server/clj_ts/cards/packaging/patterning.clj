(ns clj-ts.cards.packaging.patterning
  (:require [clj-ts.cards.patterning :as patterning]
            [clj-ts.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :patterning :html source-data (patterning/one-pattern source-data) render-context))