(ns clj-ts.cards.parsing
  {:clj-kondo/config '{:linters {:unresolved-symbol {:exclude [(better-cond.core/cond)]}}}}
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-ts.util :as util])
  (:import (java.io PushbackReader)))

(defn split-by-hyphens [input]
  (->> (str/split input #"-{4,}")
       (map str/trim)
       (remove str/blank?)))

(defn safe-read [reader]
  (try
    (edn/read reader)
    (catch Exception _e
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
          (cond
            (keyword? first-token)
            {:source-type first-token
             :source-body card-text
             #_:tokens #_[{:type :keyword
                       :value first-token}
                      {:type :unknown
                       :value (str/trim (slurp reader))}]}

            ;; note: handles cards with configuration-map only and configuration-map + body
            (type-declaring-map? first-token)
            {:source-type             (:card/type first-token)
             :source-type-configured? true
             :source-body             card-text
             #_:tokens #_[{:type :map
                       :value first-token}]}

            :else
            {:source-type nil
             :source-body card-text})))
      (catch Exception _e
        {:source-type nil
         :source-body card-text}))))

(defn raw-card-text->card-map
  "
  Parses raw-card-text and returns a map of card data.

  if raw-card-text begins with a token that can be read as a keyword (as defined by clojure.edn/read),
  then that value is used as the card source_type;

  otherwise, if raw-card-text begins with a 'type-declaring-map' (as defined by 'type-declaring-map?),
  then the value associated to key :card/type is used as the source_type;

  otherwise, the card source_type is implicitly assigned as :markdown.

  note:
  when source_type is specified via leading keyword, the resulting source_data will not include
  the keyword token.

  note:
  when source_type is specified via leading card-configuration map, the resulting source data
  will include the map, unmodified.
  "
  [raw-card-text]
  (let [{:keys [source-type source-body source-type-configured?]} (partition-raw-card-text raw-card-text)
        card-hash (util/hash-it source-body)]
    (if (nil? source-type)
      {:source_type           :markdown
       :source_type_implicit? true
       :source_data           source-body
       :hash                  card-hash}
      {:source_type             source-type
       :source_type_configured? source-type-configured?
       :source_data             source-body
       :hash                    card-hash})))

(defn raw-text->card-maps [raw]
  (->> raw
       (split-by-hyphens)
       (map raw-card-text->card-map)))

(comment

  (require 'clojure.test)
  (clojure.test/run-tests)

  ;
  )