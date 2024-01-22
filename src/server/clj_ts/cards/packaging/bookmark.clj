(ns clj-ts.cards.packaging.bookmark
  (:require [clj-ts.cards.bookmark :as bookmark]
            [clj-ts.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :bookmark :markdown source-data (bookmark/bookmark-card source-data) render-context))