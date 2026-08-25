(ns wiki.bc.cards.packaging.scheduling
  (:require [clojure.string :as s]
            [wiki.bc.storage.index :as index]
            [wiki.bc.util :as util]))

(def date-patterns [#"\d{4}/\d{1,2}/\d{1,2}"
                    #"\d{1,2}/\d{1,2}/\d{4}"
                    #"\d{4}-\d{1,2}-\d{1,2}"
                    #"\d{1,2}-\d{1,2}-\d{4}"
                    #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}"
                    #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+\d{2}:\d{2}"
                    #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+\d{2}:\d{2}\[\w+/\w+\]"])

(defn- post-match->datetime [post-match]
  (when-let [token (->> (s/split post-match #"\s+")
                        (remove s/blank?)
                        (first))]
    (when-let [found (->> date-patterns
                          (some (fn [pattern] (re-find pattern token))))]
      (util/parse-datetime found))))

(def ^:private deadline-line-pattern
  (re-pattern (str "^(.*?)(" index/deadline-marker ")(.*?)$")))

(defn- text->matches [source-page text]
  (->> (s/split-lines text)
       (keep (fn [line] (re-matches deadline-line-pattern line)))
       (map (fn [[_ _ _ post :as match]]
              {:match match
               :source-page source-page
               :datetime (post-match->datetime post)}))))

(defn package-deadline [id card-map render-context server-snapshot]
  (let [source-body (:source-body card-map)
        ;; the index hands over the flagged cards' own text, so the
        ;; line-scan is O(deadline cards); the old path text-searched
        ;; every page per render
        server-prepared-data (->> (index/deadline-cards (:page-index server-snapshot))
                                  (mapcat (fn [[page-name text]] (text->matches page-name text)))
                                  (map (fn [{:keys [match source-page datetime]}]
                                         {:match match
                                          :source-page source-page
                                          :datetime (util/datetime->iso-time datetime)}))
                                  (sort-by :datetime)
                                  (pr-str))]
    (util/package-card id :edn :deadline source-body server-prepared-data render-context)))