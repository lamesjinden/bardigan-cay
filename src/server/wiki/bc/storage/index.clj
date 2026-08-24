(ns wiki.bc.storage.index
  "Disposable Datalevin index over the page-store.

  The markdown files remain the sole source of truth. This index is
  rebuilt from scratch into a throwaway directory on every server start,
  so nothing in it survives a restart on purpose: there is no migration,
  no index backup, and no possibility of stale index state outliving a
  reboot. Schema changes are free -- the next boot rebuilds under the
  new schema.

  Writes keep the index current incrementally (index-page! after the
  file write); external edits to the page files (hand-edits, git pull)
  are picked up by a rebuild."
  (:require [clojure.java.io :as io]
            [datalevin.core :as d]
            [datalevin.interpret :refer [inter-fn]]
            [datalevin.search-utils :as search-utils])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def schema
  {:page/name          {:db/valueType :db.type/string
                        :db/unique    :db.unique/identity}
   :page/body          {:db/valueType :db.type/string
                        :db/fulltext  true}
   :page/last-modified {:db/valueType :db.type/instant}
   ;; link targets are strings, not refs: broken links are first-class
   ;; (a link routinely names a page that does not exist yet)
   :page/links         {:db/valueType   :db.type/string
                        :db/cardinality :db.cardinality/many}})

;; Full-text analyzer. The default analyzer lower-cases while tokenizing,
;; so CamelCase is gone before token filters run; this pipeline tokenizes
;; case-preserving, then splits CamelCase tokens alongside the original
;; (HelloWorld indexes as helloworld + hello + world) so that searching a
;; word finds pages whose only mention of it is inside a [[WikiLink]].

(def ^:private camel-case-token-filter
  (inter-fn [t]
            (let [word (first t)
                  parts (re-seq #"[A-Z]+(?=[A-Z][a-z0-9])|[A-Z]?[a-z0-9]+|[A-Z]+" word)]
              (if (next parts)
                (into [t] (map (fn [part] [part (nth t 1) (nth t 2)])) parts)
                [t]))))

(def ^:private page-analyzer
  (search-utils/create-analyzer
   {:tokenizer (search-utils/create-regexp-tokenizer #"[\s\p{Punct}]+")
    :token-filters [(search-utils/create-min-length-token-filter 1)
                    camel-case-token-filter
                    search-utils/lower-case-token-filter
                    search-utils/en-stop-words-token-filter]}))

;; :db/fulltext attributes index into datalevin's default search domain,
;; so the analyzer is attached under that domain name
(def ^:private index-opts
  {:search-domains {"datalevin" {:analyzer page-analyzer}}})

;; same extraction the facts-db has always used: raw text, no card parsing
(defn extract-links [body]
  (map second (re-seq #"\[\[(.+?)\]\]" body)))

(defn- page-tx-map [page-store page-name]
  (let [body (.load-page page-store page-name)
        links (distinct (extract-links body))]
    (cond-> {:page/name          page-name
             :page/body          body
             :page/last-modified (.last-modified page-store page-name)}
      (seq links) (assoc :page/links links))))

(defn- page-eid [db page-name]
  (d/q '[:find ?e . :in $ ?name :where [?e :page/name ?name]] db page-name))

(defn- scratch-dir []
  (str (Files/createTempDirectory "bc-index" (make-array FileAttribute 0))))

(defn- delete-dir! [dir]
  (doseq [file (-> dir io/file file-seq reverse)]
    (.delete file)))

;; Lifecycle

(defn open-index
  "Opens a connection over a fresh scratch directory. The result is the
  index handle passed to every other function here."
  []
  (let [dir (scratch-dir)]
    {:conn (d/get-conn dir schema index-opts)
     :dir  dir}))

(defn close!
  "Closes the connection and removes the scratch directory."
  [{:keys [conn dir]}]
  (d/close conn)
  (delete-dir! dir))

;; Indexing

(defn index-page!
  "(Re)indexes one page from the page-store: body, last-modified, links.
  Existing links are replaced, not accumulated."
  [{:keys [conn]} page-store page-name]
  (let [eid (page-eid (d/db conn) page-name)
        tx (cond->> [(page-tx-map page-store page-name)]
             eid (cons [:db.fn/retractAttribute eid :page/links]))]
    (d/transact! conn (vec tx))))

(defn unindex-page!
  "Removes a page from the index (page deleted or renamed away)."
  [{:keys [conn]} page-name]
  (when-let [eid (page-eid (d/db conn) page-name)]
    (d/transact! conn [[:db.fn/retractEntity eid]])))

(defn build!
  "Startup indexer: indexes every page in the page-store in one
  transaction. Returns the index handle."
  [{:keys [conn] :as index} page-store]
  (d/transact! conn (mapv #(page-tx-map page-store %) (.page-names page-store)))

  index)

;; Reading

(defn page-names [{:keys [conn]}]
  (sort (d/q '[:find [?name ...]
               :where [_ :page/name ?name]]
             (d/db conn))))

(defn page-exists? [{:keys [conn]} page-name]
  (some? (page-eid (d/db conn) page-name)))

(defn page-body [{:keys [conn]} page-name]
  (d/q '[:find ?body .
         :in $ ?name
         :where [?e :page/name ?name]
         [?e :page/body ?body]]
       (d/db conn) page-name))

(defn page-last-modified [{:keys [conn]} page-name]
  (d/q '[:find ?modified .
         :in $ ?name
         :where [?e :page/name ?name]
         [?e :page/last-modified ?modified]]
       (d/db conn) page-name))

(defn search-pages
  "Page names whose body matches the full-text query, most relevant
  first. Matching is token-based: case- and punctuation-insensitive,
  whole words only, with CamelCase words also matching their parts (a
  query for hello matches [[HelloWorld]]); a multi-term query returns
  pages matching any term, ranking pages that match more of them higher."
  [{:keys [conn]} query]
  (let [db (d/db conn)]
    (->> (d/fulltext-datoms db query)
         (map (fn [[eid _ _]] (:page/name (d/pull db [:page/name] eid))))
         (distinct))))
