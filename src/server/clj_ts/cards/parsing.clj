(ns clj-ts.cards.parsing
  {:clj-kondo/config '{:linters {:unresolved-symbol {:exclude [(better-cond.core/cond)]}}}}
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [better-cond.core :as b]
            [clj-ts.util :as util])
  (:import (java.io PushbackReader)))

(defn split-by-hyphens [input]
  (->> (str/split input #"-{4,}")
       (map str/trim)
       (remove str/blank?)))

(defn safe-read [reader]
  (try
    (edn/read reader)
    (catch Exception e
      nil)))

(defn type-declaring-map? [x]
  (and (map? x) (:card/type x)))

(defn partition-raw-card-text [raw-card-text]
  (let [card-text (str/trim raw-card-text)]
    (try
      (with-open [reader (-> card-text
                             (char-array)
                             (io/reader)
                             (PushbackReader.))]
        (let [first-token (safe-read reader)]
          (b/cond
            (keyword? first-token)
            {:source-type first-token
             :source-body (str/trim (slurp reader))}

            :let [second-token (safe-read reader)]

            ;; todo - add remaining token2 cases
            (and (type-declaring-map? first-token)
                 (nil? second-token))
            {:source-type (:card/type first-token)
             :source-body card-text}

            :else
            {:source-type nil
             :source-body card-text})))
      (catch Exception _e
        {:source-type nil
         :source-body card-text}))))

(defn raw-card-text->card-map
  "
  Parses raw-card-text and returns a map of card data.

  if raw-card-text begins with a value that looks like a keyword (starts with ':' followed by characters),
  then that value is treated like a keyword and used as the card source_type;
  otherwise, the card source_type is implicitly assigned as :markdown.
  "
  [raw-card-text]
  (let [{:keys [source-type source-body]} (partition-raw-card-text raw-card-text)
        card-hash (util/hash-it source-body)]
    (if (nil? source-type)
      {:source_type           :markdown
       :source_type_implicit? true
       :source_data           source-body
       :hash                  card-hash}
      {:source_type source-type
       :source_data source-body
       :hash        card-hash})))

(defn raw-text->card-maps [raw]
  (->> raw
       (split-by-hyphens)
       (map raw-card-text->card-map)))
