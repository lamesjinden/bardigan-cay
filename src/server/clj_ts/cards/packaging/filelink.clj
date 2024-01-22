(ns clj-ts.cards.packaging.filelink
  (:require [clj-ts.render :as render]
            [clj-ts.util :as util]))

(defn package [id source-data render-context]
  (util/package-card id :filelink :html source-data (render/file-link source-data) render-context))