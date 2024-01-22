(ns clj-ts.cards.packaging.raw
  (:require [clj-ts.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :raw :raw source-data source-data render-context))