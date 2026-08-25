(ns wiki.bc.query.facts-db)

(defprotocol IFactsDb
  (raw-db [db])
  (all-pages [db])
  (all-links [db])
  (broken-links [db])
  (orphan-pages [db])
  (links-to [db target])
  (transcluded-into [db target])
  (broken-transclusions [db]))
