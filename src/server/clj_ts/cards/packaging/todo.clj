(ns clj-ts.cards.packaging.todo
  (:require [clj-ts.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :todo :todo source-data source-data render-context))