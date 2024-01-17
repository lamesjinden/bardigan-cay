(ns clj-ts.cards.parsing
  (:require [clojure.string :as str]
            [hasch.core :refer [uuid5 edn-hash]]))

(defn hash-it [card-data]
  (-> card-data
      (edn-hash)
      (uuid5)))

(defn split-by-hyphens [input]
  (->> (str/split input #"-{4,}")
       (map str/trim)
       (remove str/blank?)))

(defn raw-card-text->card-map
  "
  Parses raw-card-text and returns a map of card data.

  if raw-card-text begins with a value that looks like a keyword (starts with ':' followed by characters),
  then that value is treated like a keyword and used as the card source_type;
  otherwise, the card source_type is implicitly assigned as :markdown.
  "
  [raw-card-text]
  (let [regex #"^:(\S+)" ;; todo - replace with edn/read and keyword?
        card-text (-> raw-card-text
                      (str/trim))
        card-body (-> (str/replace-first card-text regex "")
                      (str/trim))
        card-hash (hash-it card-body)]
    (if (not (re-find regex card-text))
      {:source_type           :markdown
       :source_type_implicit? true
       :source_data           card-text
       :hash                  card-hash}
      {:source_type (->> raw-card-text (re-find regex) second keyword)
       :source_data card-body
       :hash        card-hash})))

(defn raw-text->card-maps [raw]
  (->> raw
       (split-by-hyphens)
       (map raw-card-text->card-map)))

(defn package-card [id source-type render-type source-data server-prepared-data render-context]
  {:source_type          source-type
   :render_type          render-type
   :source_data          source-data
   :server_prepared_data server-prepared-data
   :id                   id
   :hash                 (hash-it source-data)
   :user_authored?       (:user-authored? render-context)})
