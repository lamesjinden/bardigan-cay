(ns wiki.bc.query.index-db
  "IFactsDb backed by the Datalevin page index (wiki.bc.storage.index).

  Replaces the core.logic pldb that was rebuilt from every page file on
  every write: the index is maintained incrementally, and each query
  here runs against a fresh immutable snapshot of it, so results are
  consistent even while a write is in flight."
  (:require [datalevin.core :as d]
            [wiki.bc.query.facts-db :as facts]
            [wiki.bc.storage.index :as index]))

(deftype IndexFactsDb [page-index conn]
  facts/IFactsDb

  (raw-db [this]
    {:page (.all-pages this)
     :link (.all-links this)})

  (all-pages [_this]
    (index/page-names page-index))

  (all-links [_this]
    (sort (d/q '[:find ?from ?to
                 :where [?e :page/name ?from]
                 [?e :page/links ?to]]
               (d/db conn))))

  (links-to [_this target]
    (sort (d/q '[:find ?from ?to
                 :in $ ?to
                 :where [?e :page/links ?to]
                 [?e :page/name ?from]]
               (d/db conn) target)))

  (broken-links [_this]
    (sort (d/q '[:find ?from ?to
                 :where [?e :page/name ?from]
                 [?e :page/links ?to]
                 (not [_ :page/name ?to])]
               (d/db conn))))

  (orphan-pages [_this]
    (sort (d/q '[:find [?name ...]
                 :where [?e :page/name ?name]
                 (not [_ :page/links ?name])]
               (d/db conn)))))

(defn make-facts-db [{:keys [conn] :as page-index}]
  (->IndexFactsDb page-index conn))
