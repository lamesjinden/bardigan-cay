(ns clj-ts.cards.packaging.scheduling
  (:require [clojure.string :as s]
            [clj-ts.render :as render]
            [clj-ts.storage.page-store :as pagestore]
            [clj-ts.util :as util])
  (:import (java.time LocalDate LocalDateTime ZonedDateTime ChronoLocalDateTime)))

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

(defn- strip-pre-formatting [pre]
  (let [strip-leading-bullets (fn [s]
                                (if-some [match (re-matches #"^\s*[\*\+\-]\s+(.+)$" s)]
                                  (get match 1)
                                  s))
        strip-leading-numerals (fn [s]
                                 (if-some [match (re-matches #"^\s*\d+\.\s+(.+)$" s)]
                                   (get match 1)
                                   s))]
    (->> [strip-leading-bullets strip-leading-numerals]
         (reduce (fn [acc f]
                   (f acc))
                 pre))))

(defn date-past-due? [datetime]
  ;; todo
  (let [now (java.time.LocalDateTime/now)]
    (cond
      (instance? java.time.LocalDateTime datetime)
      (.isBefore datetime (java.time.LocalDateTime/now))

      (instance? java.time.ZonedDateTime datetime)
      (.isBefore datetime (java.time.ZonedDateTime/now))

      (instance? java.time.ChronoLocalDateTime datetime)
      (.isBefore datetime (java.time.LocalDateTime/now))

      :else
      false)))

(defn date-due-now? [datetime] false)
(defn date-due-soon? [datetime] false)

(defn package-deadline [id card-map render-context server-snapshot]
  (let [source-body (:source-body card-map)
        server-prepared-data (let [page->matches (fn [page-name]
                                                   (let [page-text (pagestore/read-page server-snapshot page-name)]
                                                     (->> (s/split-lines page-text)
                                                          (keep (fn [line] (re-matches (re-pattern (str "^(.*?)(" deadline-pattern-str ")(.*?)$"))  line)))
                                                          (map (fn [[_ _ _ post :as match]] {:match match
                                                                                             :source-page page-name
                                                                                             :datetime (post-match->datetime post)})))))
                                   hit->display (fn [{:keys [match source-page datetime]}]
                                                  (let [[_ pre _ post] match
                                                        pre (strip-pre-formatting pre)]
                                                    (println "past due?" (date-past-due? datetime))
                                                    (cond
                                                      (date-past-due? datetime)
                                                      (format "%s[[%s|deadline:]]<span style=\"color:red;\">%s</span>" pre source-page post)

                                                      (date-due-now? datetime)
                                                      (format "%s[[%s|deadline:]]<span style=\"color:orange;\">%s</span>" pre source-page post)

                                                      (date-due-soon? datetime)
                                                      (format "%s[[%s|deadline:]]<span style=\"color:yellow;\">%s</span>" pre source-page post)

                                                      :else
                                                      (format "%s[[%s|deadline:]]%s" pre source-page post))))
                                   hits->source-data (fn [hits]
                                                       (or (seq (map hit->display hits))
                                                           ["_no deadlines_"]))
                                   all-pages (-> server-snapshot :facts-db .all-pages)
                                   pages (pagestore/text-search server-snapshot all-pages (re-pattern deadline-pattern-str))]
                               (->> pages
                                    (map page->matches)
                                    (flatten)
                                    (sort-by :datetime)
                                    (hits->source-data)
                                    (s/join "\n</br>\n")
                                    (render/md->html)))]
    (util/package-card id :markdown :deadline source-body server-prepared-data render-context)))