(ns wiki.bc.storage.page-body
  "Page-body blob store: page name -> the page's byte-exact file
  content, in a KV sub-database beside the Datalog indexes -- what
  load-page serves.

  Unlike the card-text store this one is keyed by name and mutated in
  place: every save overwrites the page's entry and unindexing deletes
  it, so there is no staleness or garbage concept here. A reader's
  fetch is a single atomic KV get, so in-place mutation is safe without
  the append-only discipline the content-addressed store needs.

  A sibling store namespace of the index (like
  wiki.bc.storage.card-text), so any consumer can depend on it without
  depending on the index."
  (:require [datalevin.core :as d]))

(def ^:private dbi "bc/page-body")

(defn open!
  "Opens the sub-database in the given env; call once per env before
  any read or write."
  [kv]
  (d/open-dbi kv dbi))

(defn body
  "The byte-exact body stored under page-name, or nil when no entry
  exists."
  [kv page-name]
  (d/get-value kv dbi page-name :string :string))

(defn put!
  "Stores every page-name -> body entry of bodies (a map or a seq of
  pairs), overwriting existing entries. Takes any KV handle -- the
  index's write path passes the transaction-scoped one so the puts join
  the core commit."
  [kv bodies]
  (d/transact-kv kv dbi
                 (mapv (fn [[page-name body]] [:put page-name body]) bodies)
                 :string :string))

(defn del!
  "Deletes the entry under page-name; a no-op when absent."
  [kv page-name]
  (d/transact-kv kv dbi [[:del page-name]] :string))
