(ns clj-ts.cards.parsing
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clj-ts.util :as util]))

(defn split-by-hyphens [input]
  (->> (str/split input #"-{4,}")
       (map str/trim)
       (remove str/blank?)))

(defn try-read [reader]
  (try
    (edn/read reader)
    (catch Exception _e
      nil)))

(defn try-read-string [s]
  (with-open [reader (util/string->reader s)]
    (try-read reader)))

(defn type-declaring-map? [x]
  (and (map? x) (:card/type x)))

(defn partition-raw-card-text [raw-card-text]
  (let [card-text (str/trim raw-card-text)]
    (try
      (with-open [reader (util/string->reader card-text)]
        (let [first-token (try-read reader)]
          (cond
            (keyword? first-token)
            {:source-type first-token
             :source-body card-text
             :tokens [{:type :keyword
                       :value first-token}
                      {:type :unknown
                       :value (str/trim (slurp reader))}]}

            (type-declaring-map? first-token)
            {:source-type             (:card/type first-token)
             :source-type-configured? true
             :source-body             card-text
             :tokens [{:type :map
                       :value first-token}
                      {:type :unknown
                       :value (str/trim (slurp reader))}]}

            ;; support card-configuration maps without types; 
            ;; still allows :markdown to be implicit
            (map? first-token)
            {:source-body card-text
             :tokens [{:type :map
                       :value first-token}
                      {:type :unknown
                       :value (str/trim (slurp reader))}]}

            :else
            {:source-body card-text})))
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
  "
  [raw-card-text]
  (let [{:keys [source-type source-body source-type-configured?] :as card-map} (partition-raw-card-text raw-card-text)
        card-hash (util/hash-it source-body)]
    (if (nil? source-type)
      (-> card-map
          (assoc :source_type           :markdown)
          (assoc :source_data           source-body)
          (assoc :source_type_implicit? true)
          (assoc :hash card-hash))
      (-> card-map
          (assoc :source_type             source-type)
          (assoc :source_data             source-body)
          (assoc :source_type_configured? source-type-configured?)
          (assoc :hash card-hash)))))

(defn raw-text->card-maps [raw]
  (->> raw
       (split-by-hyphens)
       (map raw-card-text->card-map)))

(defn card-map->card-data [card-map]
  (let [{[a b :as _tokens] :tokens} card-map
        card-data (if-let [readable-token (when (= :keyword (:type a))
                                            (:value b))]
                    (try-read-string readable-token)
                    (:value a))]
    card-data))

(comment

  (require 'clojure.test)
  (clojure.test/run-tests)

  ;
  )