(ns clj-ts.cards.packaging.embed
  (:require [clj-ts.cards.embed :as embed]
            [clj-ts.render :as render]
            [clj-ts.util :as util]))

(defn package [id source-data render-context server-snapshot]
  (let [link-renderer (if (:for-export? render-context)
                        (:link-renderer render-context)
                        (fn [s] (render/md->html s)))
        server-prepared-data (embed/process source-data render-context link-renderer server-snapshot)]
    (util/package-card id :embed :html source-data server-prepared-data render-context)))