(ns clj-ts.cards.packaging.embed
  (:require [clj-ts.cards.embed :as embed]
            [clj-ts.render :as render]
            [clj-ts.util :as util]))

(defn package [id card-map render-context server-snapshot]
  (let [source-data (:source_data card-map)
        link-renderer (if (:for-export? render-context)
                        (:link-renderer render-context)
                        (fn [s] (render/md->html s)))
        server-prepared-data (embed/process card-map render-context link-renderer server-snapshot)]
    (util/package-card id :embed :html source-data server-prepared-data render-context)))