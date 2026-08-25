(ns wiki.bc.query.index-db
  "IFactsDb backed by the Datalevin page index (wiki.bc.storage.index).

  Replaces the core.logic pldb that was rebuilt from every page file on
  every write: the index is maintained incrementally, and each query
  delegates to an index read over a fresh immutable snapshot, so results
  are consistent even while a write is in flight."
  (:require [wiki.bc.query.facts-db :as facts]
            [wiki.bc.storage.index :as index]))

(deftype IndexFactsDb [page-index]
  facts/IFactsDb

  (raw-db [this]
    {:page (.all-pages this)
     :link (.all-links this)})

  (all-pages [_this]
    (index/page-names page-index))

  (all-links [_this]
    (index/all-links page-index))

  (links-to [_this target]
    (index/links-to page-index target))

  (broken-links [_this]
    (index/broken-links page-index))

  (orphan-pages [_this]
    (index/orphan-pages page-index))

  (transcluded-into [_this target]
    (index/transcluded-into page-index target))

  (broken-transclusions [_this]
    (index/broken-transclusions page-index)))

(defn make-facts-db [page-index]
  (->IndexFactsDb page-index))
