(ns wiki.bc.cards.packaging.evalmd
  (:require [wiki.bc.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :evalmd :markdown source-data (util/server-eval source-data) render-context))