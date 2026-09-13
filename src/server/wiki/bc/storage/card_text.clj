(ns wiki.bc.storage.card-text
  "Content-addressed card-text blob store: card hash -> the card's
  split-out source text, in a KV sub-database beside the Datalog
  indexes.

  The text is the input from which the read path re-derives the parsed
  card map (raw-card-text->card-map is deterministic, so re-deriving is
  exact; the expensive page-level card splitting stays index-time
  only). Content-addressed like the search documents: the hash is a
  pure function of the text, so duplicate-hash cards share one entry
  and reorders touch nothing. Append-only at runtime -- readers resolve
  hashes from an immutable Datalog snapshot and then fetch here, so
  entries must outlive every snapshot that can reference them; orphans
  cost scratch space until the boot rebuild.

  Its own namespace rather than part of index.clj so that both the
  index (reads and transactional writes) and the reconcile process
  (reads) can depend on it without depending on each other."
  (:require [datalevin.core :as d]))

(def ^:private dbi "bc/card-text")

(defn open!
  "Opens the sub-database in the given env; call once per env before
  any read or write."
  [kv]
  (d/open-dbi kv dbi))

(defn text
  "The source text of the card whose content hash is hash-str, or nil
  when no entry exists."
  [kv hash-str]
  (d/get-value kv dbi hash-str :string :string))

(defn put-new!
  "Stores the text for every card in card-maps whose hash has no entry
  yet. Content-addressed and write-once: the hash is a pure function of
  the text, so an existing entry is guaranteed identical and is
  skipped. Takes any KV handle -- the index's write path passes the
  transaction-scoped one so the puts join the core commit."
  [kv card-maps]
  (let [new-cards (->> card-maps
                       (map (fn [card-map]
                              [(str (:hash card-map)) (:source_data card-map)]))
                       (distinct)
                       (filterv (fn [[h _text]]
                                  (nil? (text kv h)))))]
    (when (seq new-cards)
      (d/transact-kv kv dbi
                     (mapv (fn [[h t]] [:put h t]) new-cards)
                     :string :string))))
