(ns wiki.bc.cards.packaging.evalraw
  (:require [wiki.bc.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :evalraw :raw source-data (util/server-eval source-data) render-context))