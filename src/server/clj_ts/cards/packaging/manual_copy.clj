(ns clj-ts.cards.packaging.manual-copy
  (:require [clj-ts.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :manual-copy :manual-copy source-data source-data render-context))