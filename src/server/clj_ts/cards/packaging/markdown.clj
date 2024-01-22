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

(defn package [id source-data render-context]
  (util/package-card id :markdown :html source-data (render/md->html (remove-card-configuration source-data)) render-context))