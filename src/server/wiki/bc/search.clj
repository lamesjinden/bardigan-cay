(ns wiki.bc.search
  (:require [wiki.bc.storage.page-store :as pagestore]))

(defn search
  [server-snapshot pattern term]
  (let [db (-> server-snapshot :facts-db)
        all-pages (.all-pages db)
        name-matches (pagestore/name-search all-pages (re-pattern pattern))
        text-matches (pagestore/text-search server-snapshot all-pages (re-pattern pattern))]
    {:query term
     :name-matches (vec name-matches)
     :text-matches (vec text-matches)}))

(defn results->markdown
  [{:keys [query name-matches text-matches]}]
  (let [name-list (apply str (map #(str "* [[" % "]]\n") name-matches))
        text-list (apply str (map #(str "* [[" % "]]\n") text-matches))]
    (str "\n\n*" (count name-matches) " PageNames containing \"" query "\"*\n\n"
         name-list
         "\n\n*" (count text-matches) " Pages containing \"" query "\"*\n\n"
         text-list)))
