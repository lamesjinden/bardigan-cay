(ns clj-ts.search
  (:require [clj-ts.storage.page-store :as pagestore]))

(defn search
  [server-snapshot pattern term]
  (let [db (-> server-snapshot :facts-db)
        all-pages (.all-pages db)
        name-res (pagestore/name-search all-pages (re-pattern pattern))
        count-names (count name-res)
        res (pagestore/text-search server-snapshot all-pages (re-pattern pattern))
        count-res (count res)
        name-list (apply str (map #(str "* [[" % "]]\n") name-res))
        res-list (apply str (map #(str "* [[" % "]]\n") res))
        out (str "

*" count-names " PageNames containing \"" term "\"*\n
" name-list "

*" count-res " Pages containing \"" term "\"*\n
" res-list)]
    out))
