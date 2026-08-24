(ns wiki.bc.search
  (:require [clojure.string :as string]
            [wiki.bc.storage.index :as index]
            [wiki.bc.storage.page-store :as pagestore]))

(defn search
  "Page-name matches are a case-insensitive substring test against the
  pattern; text matches come from the page index's full-text engine
  (token-based, relevance-ranked -- see index/search-pages). A blank
  query matches nothing."
  [server-snapshot pattern term]
  (if (string/blank? term)
    {:query term
     :name-matches []
     :text-matches []}
    (let [db (-> server-snapshot :facts-db)
          all-pages (.all-pages db)
          name-matches (pagestore/name-search all-pages (re-pattern pattern))
          text-matches (index/search-pages (:page-index server-snapshot) term)]
      {:query term
       :name-matches (vec name-matches)
       :text-matches (vec text-matches)})))

(defn results->markdown
  [{:keys [query name-matches text-matches]}]
  (let [name-list (apply str (map #(str "* [[" % "]]\n") name-matches))
        text-list (apply str (map #(str "* [[" % "]]\n") text-matches))]
    (str "\n\n*" (count name-matches) " PageNames containing \"" query "\"*\n\n"
         name-list
         "\n\n*" (count text-matches) " Pages containing \"" query "\"*\n\n"
         text-list)))
