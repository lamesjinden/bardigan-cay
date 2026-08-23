(ns wiki.bc.cards.packaging.embed
  (:require [wiki.bc.cards.embed :as embed]
            [wiki.bc.render :as render]
            [wiki.bc.util :as util]))

(defn package [id card-map render-context server-snapshot]
  (let [source-data (:source_data card-map)
        link-renderer (fn [s] (render/md->html s))
        server-prepared-data (embed/process card-map render-context link-renderer server-snapshot)]
    (util/package-card id :embed :html source-data server-prepared-data render-context)))