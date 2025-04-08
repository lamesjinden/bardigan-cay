(ns clj-ts.cards.packaging.scheduling
  (:require [clojure.string :as s]
            [clj-ts.storage.page-store :as pagestore]
            [clj-ts.util :as util]))

(def deadline-pattern-str "deadline:")

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

(defn- page->matches [server-snapshot page-name]
  (let [page-text (pagestore/read-page server-snapshot page-name)]
    (->> (s/split-lines page-text)
         (keep (fn [line] (re-matches (re-pattern (str "^(.*?)(" deadline-pattern-str ")(.*?)$")) line)))
         (map (fn [[_ _ _ post :as match]]
                {:match match
                 :source-page page-name
                 :datetime (post-match->datetime post)})))))

(defn package-deadline [id card-map render-context server-snapshot]
  (let [source-body (:source-body card-map)
        server-prepared-data (let [all-pages (-> server-snapshot :facts-db .all-pages)
                                   pages (pagestore/text-search server-snapshot all-pages (re-pattern deadline-pattern-str))]
                               (->> pages
                                    (map (partial page->matches server-snapshot))
                                    (mapcat identity)
                                    (map (fn [{:keys [match source-page datetime]}]
                                           {:match match
                                            :source-page source-page
                                            :datetime (util/datetime->iso-time datetime)}))
                                    (sort-by :datetime)
                                    (pr-str)))]
    (util/package-card id :edn :deadline source-body server-prepared-data render-context)))