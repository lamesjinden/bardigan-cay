(ns wiki.bc.storage.index
  "Disposable Datalevin index over the page-store.

  The markdown files remain the sole source of truth. This index is
  rebuilt from scratch into a throwaway directory on every server start,
  so nothing in it survives a restart on purpose: there is no migration,
  no index backup, and no possibility of stale index state outliving a
  reboot. Schema changes are free -- the next boot rebuilds under the
  new schema.

  Pages are indexed together with their parsed cards: each card is its
  own entity carrying its position, content hash, declared :card/id (when
  present), links, and its full-text-indexed source text. Reading a
  page's cards or looking one up by hash or id is index lookup plus a
  cheap per-card re-derivation from the stored text; the expensive
  page-level card splitting happens once per write, in index-page!.

  Writes keep the index current incrementally (index-page! after the
  file write); external edits to the page files (hand-edits, git pull)
  are picked up by refresh-page! on the write path or by a rebuild."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [datalevin.core :as d]
            [datalevin.interpret :refer [inter-fn]]
            [datalevin.search-utils :as search-utils]
            [wiki.bc.cards.parsing :as parsing])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def schema
  {:page/name          {:db/valueType :db.type/string
                        :db/unique    :db.unique/identity}
   ;; byte-exact file content, served by load-page; full-text search runs
   ;; over the per-card :card/text instead
   :page/body          {:db/valueType :db.type/string}
   :page/last-modified {:db/valueType :db.type/instant}
   :card/page          {:db/valueType :db.type/ref}
   :card/idx           {:db/valueType :db.type/long}
   ;; hash-it returns a UUID; stored as its string form, which is what
   ;; transclusion :ids carry
   :card/hash          {:db/valueType :db.type/string}
   ;; declared {:card/id ...} from the card's configuration map, stored
   ;; as its pr-str so non-string ids (keywords, numbers) resolve while
   ;; "42" and 42 stay distinct -- mirroring card-match's = semantics
   :card/id            {:db/valueType :db.type/string}
   ;; link targets are strings, not refs: broken links are first-class
   ;; (a link routinely names a page that does not exist yet)
   :card/links         {:db/valueType   :db.type/string
                        :db/cardinality :db.cardinality/many}
   ;; the :from page a :transclude card pulls content from -- kept apart
   ;; from :card/links so transclusion edges can be shown and queried as
   ;; their own relation (also a string: the source may not exist)
   :card/transcludes-from {:db/valueType :db.type/string}
   ;; asserted (true) on cards whose text contains deadline-marker, so
   ;; the deadline card can query candidate pages instead of scanning the
   ;; whole wiki at render time
   :card/deadline?     {:db/valueType :db.type/boolean}
   ;; the card's split-out source text, doing double duty: full-text search
   ;; document, and the input from which the read path re-derives the parsed
   ;; card map (raw-card-text->card-map is deterministic, so re-deriving is
   ;; exact; the expensive page-level card splitting stays index-time only)
   :card/text          {:db/valueType :db.type/string
                        :db/fulltext  true}})

;; Full-text analyzer. The default analyzer lower-cases while tokenizing,
;; so CamelCase is gone before token filters run; this pipeline tokenizes
;; case-preserving, then splits CamelCase tokens alongside the original
;; (HelloWorld indexes as helloworld + hello + world) so that searching a
;; word finds pages whose only mention of it is inside a [[WikiLink]].
;;
;; The sci inter-fn pipeline is REQUIRED, not a style choice: datalevin
;; nippy-freezes the connection opts (including the analyzer) into LMDB
;; at get-conn, and only inter-fns serialize -- swapping in the plain
;; compiled fns from datalevin.analyzer fails every transact with
;; "Failed to freeze type". The interpreter cost is paid per token at
;; boot indexing and per query term.

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

;; lives here rather than in the scheduling packager because the marker is
;; part of the indexing contract: cards containing it are flagged
;; :card/deadline? at index time, and the packager (which requires this ns)
;; line-scans only the flagged cards
(def deadline-marker "deadline:")

(defn- card-tx-map [page-tempid idx card-map]
  (let [links (distinct (extract-links (:source_data card-map)))
        card-data (parsing/card-map->card-data card-map)
        declared-id (:card/id card-data)
        transcludes-from (when (= :transclude (:source_type card-map))
                           (:from card-data))]
    (cond-> {:card/page page-tempid
             :card/idx  idx
             :card/hash (str (:hash card-map))
             :card/text (:source_data card-map)}
      (some? declared-id)        (assoc :card/id (pr-str declared-id))
      (string? transcludes-from) (assoc :card/transcludes-from transcludes-from)
      (string/includes? (:source_data card-map) deadline-marker) (assoc :card/deadline? true)
      (seq links)                (assoc :card/links links))))

(defn- page-tx-maps
  "Transaction maps for one page: the page entity followed by one entity
  per parsed card. The page tempid is name-derived so build! can transact
  every page in a single call. The write path passes the body it just
  wrote so the file is not read back from disk."
  ([page-store page-name]
   (page-tx-maps page-store page-name (.load-page page-store page-name)))
  ([page-store page-name body]
   (let [card-maps (parsing/raw-text->card-maps body)
         tempid (str "page-" page-name)]
     (into [{:db/id              tempid
             :page/name          page-name
             :page/body          body
             :page/last-modified (.last-modified page-store page-name)}]
           (map-indexed (fn [idx card-map] (card-tx-map tempid idx card-map))
                        card-maps)))))

;; :page/name is unique-identity, so every page read resolves through a
;; lookup ref; the page<->card join then lives in this one spot -- card
;; queries take the resolved eid instead of re-stating the join.

(defn- page-eid [db page-name]
  (d/entid db [:page/name page-name]))

(defn- card-eids [db page-name]
  (when-let [peid (page-eid db page-name)]
    (d/q '[:find [?c ...]
           :in $ ?p
           :where [?c :card/page ?p]]
         db peid)))

(defn- card-texts
  "The card texts of page-name in page order."
  [db page-name]
  (when-let [peid (page-eid db page-name)]
    (->> (d/q '[:find ?idx ?text
                :in $ ?p
                :where [?c :card/page ?p]
                [?c :card/idx ?idx]
                [?c :card/text ?text]]
              db peid)
         (sort-by first)
         (mapv second))))

(defn- card-rows
  "[[idx eid hash] ...] for page-name's cards, sorted by idx."
  [db page-name]
  (when-let [peid (page-eid db page-name)]
    (sort-by first
             (d/q '[:find ?idx ?c ?h
                    :in $ ?p
                    :where [?c :card/page ?p]
                    [?c :card/idx ?idx]
                    [?c :card/hash ?h]]
                  db peid))))

(defn- target-card
  "[idx eid locator] of the card in page-name whose content hash or
  declared :card/id matches hash-or-id, or nil. Mirrors cards/card-match
  over the parsed card list: cards are considered in page order and the
  earliest match wins whether it matched by hash or by id; on a card
  matching both, hash wins. Declared ids are compared by pr-str, so
  non-string ids (keywords, numbers) resolve just as card-match's plain
  = did."
  [db page-name hash-or-id]
  (when-let [peid (page-eid db page-name)]
    (let [matches (fn [attr-q value locator-rank locator]
                    (map (fn [[idx eid]] [idx locator-rank eid locator])
                         (d/q attr-q db peid value)))
          candidates (concat
                      (matches '[:find ?idx ?c
                                 :in $ ?p ?v
                                 :where [?c :card/page ?p]
                                 [?c :card/hash ?v]
                                 [?c :card/idx ?idx]]
                               (str hash-or-id) 0 :hash)
                      (matches '[:find ?idx ?c
                                 :in $ ?p ?v
                                 :where [?c :card/page ?p]
                                 [?c :card/id ?v]
                                 [?c :card/idx ?idx]]
                               (pr-str hash-or-id) 1 :id))]
      ;; secondary sort key breaks the same-card tie in favor of :hash
      (when-let [[idx _ eid locator] (first (sort-by (juxt first second) candidates))]
        [idx eid locator]))))

(defn- single-card?
  "True when a card map's text would re-parse as exactly one card -- the
  precondition for indexing it card-scoped: text holding an unprotected
  ---- delimiter line splits into several cards on a full re-parse, so a
  single-entity update would drift from what a rebuild produces."
  [card-map]
  (= 1 (count (parsing/split-by-hyphens (:source_data card-map)))))

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
  "(Re)indexes one page from the page-store: body, last-modified, and its
  parsed cards. Existing card entities are replaced, not accumulated.
  The write path passes the body it just wrote; the 3-arity reads it
  from the store."
  ([index page-store page-name]
   (index-page! index page-store page-name (.load-page page-store page-name)))
  ([{:keys [conn]} page-store page-name body]
   (let [retractions (mapv (fn [eid] [:db.fn/retractEntity eid])
                           (card-eids (d/db conn) page-name))]
     (d/transact! conn (into retractions (page-tx-maps page-store page-name body))))))

(defn- delta-tx
  "Transaction data applying a card-scoped delta to page-name, or nil
  when the delta cannot faithfully reproduce what a full re-parse of the
  written file would build (the caller then falls back to index-page!).
  Every delta also refreshes the page's body and last-modified."
  [db page-store page-name body {:keys [op target card hashes]}]
  (when-let [peid (page-eid db page-name)]
    (let [page-update {:db/id              peid
                       :page/body          body
                       :page/last-modified (.last-modified page-store page-name)}
          rows (card-rows db page-name)]
      (case op
        :replace
        (when (single-card? card)
          (when-let [[idx eid _locator] (target-card db page-name target)]
            [page-update
             [:db.fn/retractEntity eid]
             (card-tx-map peid idx card)]))

        :remove
        (when-let [[idx eid _locator] (target-card db page-name target)]
          (into [page-update
                 [:db.fn/retractEntity eid]]
                (for [[i e _h] rows
                      :when (> i idx)]
                  [:db/add e :card/idx (dec i)])))

        :append
        (when (single-card? card)
          (let [next-idx (if (seq rows)
                           (inc (first (last rows)))
                           0)]
            [page-update
             (card-tx-map peid next-idx card)]))

        :reorder
        (when (= (count hashes) (count rows))
          ;; earliest-first pools per hash: equal-hash cards are
          ;; interchangeable (identical content), so greedy assignment
          ;; still lands the index in the rebuilt state
          (let [pools (reduce (fn [m [idx eid h]]
                                (update m h (fnil conj clojure.lang.PersistentQueue/EMPTY) [idx eid]))
                              {}
                              rows)]
            (loop [n 0
                   pools pools
                   adds []]
              (if (= n (count hashes))
                (into [page-update] adds)
                (when-let [[idx eid] (peek (get pools (nth hashes n)))]
                  (recur (inc n)
                         (update pools (nth hashes n) pop)
                         (if (= idx n)
                           adds
                           (conj adds [:db/add eid :card/idx n]))))))))))))

(defn index-card-delta!
  "Card-scoped reindex of page-name after a card-level write: applies the
  delta as a minimal transaction instead of re-parsing the page. Falls
  back to a full index-page! whenever the delta cannot be applied
  exactly -- replacement/appended text that would split into several
  cards, an unknown target, or index state that has drifted from the
  file. The write path passes the body it just wrote; the 4-arity reads
  it from the store.

  Delta shapes:
    {:op :replace :target hash-or-id :card card-map}
    {:op :remove  :target hash-or-id}
    {:op :append  :card card-map}
    {:op :reorder :hashes [hash-string ...]}   ; the new card order"
  ([index page-store page-name delta]
   (index-card-delta! index page-store page-name
                      (.load-page page-store page-name) delta))
  ([{:keys [conn] :as index} page-store page-name body delta]
   (let [tx (try
              (delta-tx (d/db conn) page-store page-name body delta)
              (catch Exception _
                nil))]
     (if (nil? tx)
       (index-page! index page-store page-name body)
       (do
         (d/transact! conn tx)
         ;; post-write invariant check: the applied cards must equal what a
         ;; re-split of the written body yields. This catches every way a
         ;; scoped delta can under-describe the file -- a multi-match
         ;; remove, a replacement whose unclosed code fence swallows the
         ;; next delimiter on a whole-page parse, a wrong-target
         ;; resolution -- and heals with a full re-parse.
         (when (not= (vec (parsing/split-by-hyphens body))
                     (card-texts (d/db conn) page-name))
           (index-page! index page-store page-name body)))))))

(defn unindex-page!
  "Removes a page and its cards from the index (page deleted or renamed
  away)."
  [{:keys [conn]} page-name]
  (let [db (d/db conn)
        peid (page-eid db page-name)
        eids (cond-> (vec (card-eids db page-name))
               peid (conj peid))]
    (when (seq eids)
      (d/transact! conn (mapv (fn [eid] [:db.fn/retractEntity eid]) eids)))))

(defn build!
  "Startup indexer: indexes every page in the page-store. Pages are
  transacted in batches: LMDB caps how much one transaction may dirty,
  so a single transaction over a whole wiki of card entities fails with
  MDB_PAGE_FULL. Returns the index handle."
  [{:keys [conn] :as index} page-store]
  (doseq [batch (partition-all 64 (.page-names page-store))]
    (d/transact! conn (into []
                            (mapcat (fn [page-name] (page-tx-maps page-store page-name)))
                            batch)))

  index)

;; Reading

(defn page-names [{:keys [conn]}]
  (sort (d/q '[:find [?name ...]
               :where [_ :page/name ?name]]
             (d/db conn))))

(defn page-exists? [{:keys [conn]} page-name]
  (some? (page-eid (d/db conn) page-name)))

(defn page-body [{:keys [conn]} page-name]
  (:page/body (d/pull (d/db conn) [:page/body] [:page/name page-name])))

(defn page-last-modified [{:keys [conn]} page-name]
  (:page/last-modified
   (d/pull (d/db conn) [:page/last-modified] [:page/name page-name])))

(defn refresh-page!
  "Heals the index for page-name when the file on disk changed outside
  the write path (hand edit, git pull, file created or deleted after
  boot): reindexes on mtime drift or a new file, unindexes when the file
  is gone, no-ops when file and index agree. Callers that read cards in
  order to write them back MUST call this first, or a stale index copy
  would overwrite the external edit."
  [index page-store page-name]
  (let [on-disk? (.page-exists? page-store page-name)
        indexed-mtime (page-last-modified index page-name)]
    (cond
      (and on-disk?
           (or (nil? indexed-mtime)
               (not= (.getTime indexed-mtime)
                     (.getTime (.last-modified page-store page-name)))))
      (index-page! index page-store page-name)

      (and (not on-disk?) (some? indexed-mtime))
      (unindex-page! index page-name))))

(defn page-cards
  "The parsed card maps of a page, in page order. Empty when the page has
  no cards or is not indexed."
  [{:keys [conn]} page-name]
  (map parsing/raw-card-text->card-map (card-texts (d/db conn) page-name)))

(defn lookup-card
  "The card in page-name whose content hash or declared :card/id matches
  hash-or-id, as [card-map locator] where locator is :hash or :id -- or
  nil. Resolution order mirrors cards/card-match over the parsed card
  list: the earliest matching card wins whether it matched by hash or by
  id (see target-card)."
  [{:keys [conn]} page-name hash-or-id]
  (let [db (d/db conn)]
    ;; resolve to an eid first, then pull the one card's text: fetching
    ;; texts inside the join would drag every card on the page along
    (when-let [[_idx eid locator] (target-card db page-name hash-or-id)]
      [(-> (d/pull db [:card/text] eid)
           (:card/text)
           (parsing/raw-card-text->card-map))
       locator])))

;; Link graph

(defn all-links
  "Sorted [from to] pairs for every wiki-link in every card."
  [{:keys [conn]}]
  (sort (d/q '[:find ?from ?to
               :where [?p :page/name ?from]
               [?c :card/page ?p]
               [?c :card/links ?to]]
             (d/db conn))))

(defn links-to
  "Sorted [from target] pairs for the pages wiki-linking to target."
  [{:keys [conn]} target]
  (sort (d/q '[:find ?from ?to
               :in $ ?to
               :where [?c :card/links ?to]
               [?c :card/page ?p]
               [?p :page/name ?from]]
             (d/db conn) target)))

(defn broken-links
  "Sorted [from to] pairs whose link target is not a page."
  [{:keys [conn]}]
  (sort (d/q '[:find ?from ?to
               :where [?p :page/name ?from]
               [?c :card/page ?p]
               [?c :card/links ?to]
               (not [_ :page/name ?to])]
             (d/db conn))))

(defn orphan-pages
  "Sorted names of pages with no inbound wiki-link and no inbound
  transclusion."
  [{:keys [conn]}]
  (sort (d/q '[:find [?name ...]
               :where [?p :page/name ?name]
               (not [_ :card/links ?name])
               (not [_ :card/transcludes-from ?name])]
             (d/db conn))))

(defn transcluded-into
  "Names of the pages holding a :transclude card that pulls from target,
  sorted."
  [{:keys [conn]} target]
  (sort (d/q '[:find [?name ...]
               :in $ ?target
               :where [?c :card/transcludes-from ?target]
               [?c :card/page ?p]
               [?p :page/name ?name]]
             (d/db conn) target)))

(defn deadline-cards
  "[page-name card-text] of every card containing deadline-marker,
  sorted by page then position on the page -- exactly the text the
  deadline card line-scans, replacing the old every-page text search.
  A line can never span a card boundary (delimiters are whole lines),
  so scanning flagged cards sees every line the page scan saw."
  [{:keys [conn]}]
  (->> (d/q '[:find ?name ?idx ?text
              :where [?c :card/deadline? true]
              [?c :card/idx ?idx]
              [?c :card/text ?text]
              [?c :card/page ?p]
              [?p :page/name ?name]]
            (d/db conn))
       (sort-by (fn [[name idx _text]] [name idx]))
       (map (fn [[name _idx text]] [name text]))))

(defn broken-transclusions
  "Transclude cards whose source does not resolve, sorted by transcluding
  page then source page. Entries are
  {:page P :from F :missing-page true} when the :from page does not
  exist, {:page P :from F :malformed-ids v} when :ids is missing or not
  a sequence (such a card renders nothing), else
  {:page P :from F :missing-ids [id ...]} listing the ids (hashes or
  declared :card/ids) that no longer name a card there. Resolution is
  lookup-card, the same one rendering uses, so an entry here is exactly
  a transclusion that renders incomplete."
  [{:keys [conn] :as index}]
  (->> (d/q '[:find ?pname ?from ?text
              :where [?c :card/transcludes-from ?from]
              [?c :card/text ?text]
              [?c :card/page ?p]
              [?p :page/name ?pname]]
            (d/db conn))
       (keep (fn [[pname from text]]
               (if-not (page-exists? index from)
                 {:page pname :from from :missing-page true}
                 (let [ids (:ids (parsing/card-map->card-data
                                  (parsing/raw-card-text->card-map text)))]
                   (if (sequential? ids)
                     (let [missing (vec (remove (fn [id] (lookup-card index from id)) ids))]
                       (when (seq missing)
                         {:page pname :from from :missing-ids missing}))
                     {:page pname :from from :malformed-ids ids})))))
       (sort-by (juxt :page :from))
       (vec)))

(defn search-pages
  "Page names with a card matching the full-text query, most relevant
  first. Matching is token-based: case- and punctuation-insensitive,
  whole words only, with CamelCase words also matching their parts (a
  query for hello matches [[HelloWorld]]); a multi-term query returns
  pages matching any term, ranking pages whose cards match more of them
  higher."
  [{:keys [conn]} query]
  (let [db (d/db conn)]
    (->> (d/fulltext-datoms db query)
         (map (fn [[eid _ _]]
                (get-in (d/pull db [{:card/page [:page/name]}] eid)
                        [:card/page :page/name])))
         (remove nil?)
         (distinct))))
