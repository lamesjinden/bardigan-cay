(ns wiki.bc.query.git-facts-db
  "IFactsDb for a wiki viewed at a git revision.

  Only the page list is answered, straight from the revision's tree.
  The link-graph queries need an index of every page at that commit,
  which nothing builds for a transient snapshot, so they answer
  :not-available and the system cards say so."
  (:require [wiki.bc.query.facts-db :as facts]
            [wiki.bc.storage.page-storage :as page-storage]))

(defn make-git-facts-db [git-page-store]
  (reify facts/IFactsDb
    (raw-db [_this] :not-available)
    (all-pages [_this] (page-storage/page-names git-page-store))
    (all-links [_this] :not-available)
    (broken-links [_this] :not-available)
    (orphan-pages [_this] :not-available)
    (links-to [_this _target] :not-available)
    (transcluded-into [_this _target] :not-available)
    (broken-transclusions [_this] :not-available)))
