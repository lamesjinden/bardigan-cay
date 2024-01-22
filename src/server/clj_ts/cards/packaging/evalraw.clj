(ns clj-ts.cards.packaging.evalraw
  (:require [clj-ts.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :evalraw :raw source-data (util/server-eval source-data) render-context))