(ns clj-ts.cards.packaging.evalmd
  (:require [clj-ts.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :evalmd :markdown source-data (util/server-eval source-data) render-context))