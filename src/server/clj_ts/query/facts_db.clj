(ns clj-ts.query.facts-db)

(defprotocol IFactsDb
  (raw-db [db])
  (all-pages [db])
  (all-links [db])
  (broken-links [db])
  (orphan-pages [db])
  (links-to [db target]))
