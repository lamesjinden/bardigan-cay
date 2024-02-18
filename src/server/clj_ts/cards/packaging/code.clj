(ns clj-ts.cards.packaging.code
  (:require [clj-ts.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :code :code source-data source-data render-context))