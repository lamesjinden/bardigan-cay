(ns clj-ts.cards.packaging.markdown
  (:require [clj-ts.render :as render]
            [clj-ts.util :as util]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.io PushbackReader)))

(defn- remove-card-configuration [source_data]
  (try
    (with-open [reader (-> source_data
                           (char-array)
                           (io/reader)
                           (PushbackReader.))]
      (let [edn (edn/read reader)]
        (if (map? edn)
          (slurp reader)
          source_data)))
    (catch Exception _e
      source_data)))

(defn package [id card-map render-context]
  (let [{source-data       :source_data
         [a b :as _tokens] :tokens} card-map
        server-prepared-data (render/md->html (cond
                                                (nil? (:type a)) (remove-card-configuration source-data)
                                                (= :keyword (:type a)) (remove-card-configuration (:value b))
                                                (= :map (:type a)) (remove-card-configuration source-data)
                                                :else (throw (ex-info (format "unknown token type: %s" (:type a)) {}))))]
    (util/package-card id :markdown :html source-data server-prepared-data  render-context)))