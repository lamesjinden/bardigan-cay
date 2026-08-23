(ns wiki.bc.cards.packaging.code
  (:require [wiki.bc.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :code :code source-data source-data render-context))