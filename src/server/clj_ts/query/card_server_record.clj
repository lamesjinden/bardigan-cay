(ns clj-ts.query.card-server-record
  (:require [clj-ts.query.facts-db :as facts]))

(defmacro dnn [cs m & args]
  `(let [db# (:facts-db ~cs)]
     (if (nil? db#)
       :not-available
       (. db# ~m ~@args))))

(defrecord CardServerRecord
           [wiki-name site-url port-no start-page nav-links facts-db page-store]

  facts/IFactsDb
  (raw-db [this] (dnn this raw-db))
  (all-pages [this] (dnn this all-pages))
  (all-links [this] (dnn this all-links))
  (broken-links [this] (dnn this broken-links))
  (orphan-pages [this] (dnn this orphan-pages))
  (links-to [this page-name] (dnn this links-to page-name)))
