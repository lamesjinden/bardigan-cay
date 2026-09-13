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
  present), and links; the card's source text lives in a content-addressed
  KV sub-database and search runs over a standalone engine keyed the same
  way. Reading a page's cards or looking one up by hash or id is index
  lookup plus a cheap per-card re-derivation from the stored text; the
  expensive page-level card splitting happens once per write, in
  index-page!.

  Writes keep the index current incrementally (index-page! after the
  file write); external edits to the page files (hand-edits, git pull)
  are picked up by refresh-page! on the write path or by a rebuild."
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [datalevin.core :as d]
            [datalevin.analyzer :as analyzer]
            [taoensso.timbre :refer [warn]]
            [wiki.bc.cards.parsing :as parsing]
            [wiki.bc.storage.card-text :as card-text]
            [wiki.bc.storage.page-body :as page-body]
            [wiki.bc.storage.reconcile :as reconcile])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def schema
  {:page/name          {:db/valueType :db.type/string
                        :db/unique    :db.unique/identity}
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
   :card/deadline?     {:db/valueType :db.type/boolean}})

;; Full-text analyzer. The default analyzer lower-cases while tokenizing,
;; so CamelCase is gone before token filters run; this pipeline tokenizes
;; case-preserving, then splits CamelCase tokens alongside the original
;; (HelloWorld indexes as helloworld + hello + world) so that searching a
;; word finds pages whose only mention of it is inside a [[WikiLink]].
;;
;; These are datalevin.analyzer's plain compiled fns. The engine takes the
;; analyzer at construction and keeps it in memory, so nothing here is
;; serialized -- unlike the retired :db/fulltext setup, whose analyzer was
;; nippy-frozen into LMDB with the conn opts and therefore had to be built
;; from sci inter-fns, paying interpreter cost per token.

(defn- camel-case-token-filter [t]
  (let [word (first t)
        parts (re-seq #"[A-Z]+(?=[A-Z][a-z0-9])|[A-Z]?[a-z0-9]+|[A-Z]+" word)]
    (if (next parts)
      (into [t] (map (fn [part] [part (nth t 1) (nth t 2)])) parts)
      [t])))

(def ^:private page-analyzer
  (analyzer/create-analyzer
   {:tokenizer (analyzer/create-regexp-tokenizer #"[\s\p{Punct}]+")
    :token-filters [(analyzer/create-min-length-token-filter 1)
                    camel-case-token-filter
                    analyzer/lower-case-token-filter
                    analyzer/en-stop-words-token-filter]}))

;; Large text lives in KV sub-databases beside the Datalog indexes (same
;; env, via datalog-kv), not in datom values: datom values are encoded
;; into LMDB index keys, so multi-KB texts would each allocate a "giant"
;; side-table entry, while a KV value is an ordinary LMDB value with no
;; such limit (see docs/index-blob-store-design.md). The stores are the
;; sibling namespaces wiki.bc.storage.page-body and
;; wiki.bc.storage.card-text, so consumers like the reconcile process
;; can share them without depending on this namespace.

;; Search engine over card texts (steps 2-3 of
;; docs/index-blob-store-design.md). Documents are content-addressed: the
;; doc-ref is the card's hash string, which is a pure function of the card
;; text, so duplicate-hash cards across the wiki share one document and
;; reorders touch nothing. The domain name prefixes the engine's
;; sub-databases ("bc-cards/...") in the shared env.
(def ^:private card-search-domain "bc-cards")

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
             :card/hash (str (:hash card-map))}
      (some? declared-id)        (assoc :card/id (pr-str declared-id))
      (string? transcludes-from) (assoc :card/transcludes-from transcludes-from)
      (string/includes? (:source_data card-map) deadline-marker) (assoc :card/deadline? true)
      (seq links)                (assoc :card/links links))))

(defn- page-tx-maps
  "Transaction maps for one page: the page entity followed by one entity
  per parsed card. The page tempid is name-derived so build! can transact
  every page in a single call. Callers parse the body once and pass the
  card maps in, because the same parse also feeds the body KV write and
  the search-document sync."
  [page-store page-name card-maps]
  (let [tempid (str "page-" page-name)]
    (into [{:db/id              tempid
            :page/name          page-name
            :page/last-modified (.last-modified page-store page-name)}]
          (map-indexed (fn [idx card-map] (card-tx-map tempid idx card-map))
                       card-maps))))

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

(defn- card-texts
  "The card texts of page-name in page order."
  [{:keys [conn kv]} page-name]
  (mapv (fn [[_idx _eid h]] (card-text/text kv h))
        (card-rows (d/db conn) page-name)))

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

;; Core transaction

(defn- commit-core!
  "Runs f inside one LMDB write transaction spanning the Datalog index
  and both KV sub-databases, passing it a transaction-scoped handle
  {:conn tx-conn :kv tx-kv}. Everything f writes -- datoms, page body,
  card texts -- commits or aborts as a unit, so the write path needs no
  ordering protocol or commit marker between the stores. Returns f's
  result; an exception aborts the whole transaction and rethrows.

  The search engine must never be written inside f: its in-memory index
  does not participate in aborts, so an enrolled engine survives a
  rollback in divergent form (the failure mode that permanently wedged
  the old :db/fulltext path in production). Engine sync belongs after
  the commit, on the reconcile process (see wiki.bc.storage.reconcile)."
  [{:keys [conn]} f]
  (d/with-transaction [tx conn]
    (f {:conn tx :kv (d/datalog-kv tx)})))

;; Lifecycle

(defn open-index
  "Opens a connection over a fresh scratch directory and starts the
  search reconciliation process over it. The result is the index handle
  passed to every other function here: the stores plus the process's
  two channels -- notify$ (its intake) and reconcile$ (its completion).
  The KV handle and the search engine share the connection's env."
  []
  (let [dir (scratch-dir)
        conn (d/get-conn dir schema)
        kv (d/datalog-kv conn)]
    (page-body/open! kv)
    (card-text/open! kv)
    (let [index {:conn   conn
                 :kv     kv
                 :engine (d/new-search-engine kv {:domain   card-search-domain
                                                  :analyzer page-analyzer})
                 :dir    dir}
          notify$ (a/chan 64)]
      (assoc index
             :notify$ notify$
             :reconcile$ (reconcile/create-reconcile-process
                          notify$
                          index)))))

(defn close!
  "Ends the reconciliation process by closing its intake (closure
  propagates: it drains and exits), then closes the connection and
  removes the scratch directory. Should the process fail to finish in
  time, the env is deliberately LEAKED: closing LMDB underneath a live
  thread is native undefined behavior (the crash class), while a leaked
  scratch env only wastes tmp space until reboot."
  [{:keys [conn dir notify$ reconcile$]}]
  (a/close! notify$)
  (let [[_ port] (a/alts!! [reconcile$ (a/timeout 5000)])]
    (if (= port reconcile$)
      (do
        (d/close conn)
        (delete-dir! dir))
      (warn "search reconcile process did not finish before close;"
            "leaking the scratch env rather than closing it under a live thread:"
            dir))))

;; Indexing

(defn index-page!
  "(Re)indexes one page from the page-store: body, last-modified, and its
  parsed cards. Existing card entities are replaced, not accumulated.
  The write path passes the body it just wrote; the 3-arity reads it
  from the store."
  ([index page-store page-name]
   (index-page! index page-store page-name (.load-page page-store page-name)))
  ([index page-store page-name body]
   (let [card-maps (parsing/raw-text->card-maps body)
         old-hashes
         (commit-core! index
                       (fn [{tconn :conn tkv :kv}]
                         (let [db (d/db tconn)
                               old-hashes (mapv (fn [[_idx _eid h]] h) (card-rows db page-name))
                               retractions (mapv (fn [eid] [:db.fn/retractEntity eid])
                                                 (card-eids db page-name))]
                           (page-body/put! tkv {page-name body})
                           (card-text/put-new! tkv card-maps)
                           (d/transact! tconn (into retractions (page-tx-maps page-store page-name card-maps)))
                           old-hashes)))]
     (reconcile/notify! (:notify$ index)
                        (mapv (fn [card-map] (str (:hash card-map))) card-maps)
                        old-hashes))))

(defn- delta-tx
  "Transaction data applying a card-scoped delta to page-name, or nil
  when the delta cannot faithfully reproduce what a full re-parse of the
  written file would build (the caller then falls back to index-page!).
  Every delta also refreshes the page's last-modified (the body refresh
  is the caller's KV write)."
  [db page-store page-name {:keys [op target card hashes]}]
  (when-let [peid (page-eid db page-name)]
    (let [page-update {:db/id              peid
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
  ([index page-store page-name body delta]
   (let [result
         (try
           (commit-core! index
                         (fn [{tconn :conn :as tx-handle}]
                           (let [db (d/db tconn)
                                 tx (try
                                      (delta-tx db page-store page-name delta)
                                      (catch Exception _
                                        nil))
                     ;; a reorder moves no text, so its docs cannot go stale
                                 old-hashes (when (not= :reorder (:op delta))
                                              (mapv (fn [[_idx _eid h]] h) (card-rows db page-name)))]
                             (if (nil? tx)
                               ::fallback
                               (do
                                 (page-body/put! (:kv tx-handle) {page-name body})
                                 (when-let [card (:card delta)]
                                   (card-text/put-new! (:kv tx-handle) [card]))
                                 (d/transact! tconn tx)
                     ;; invariant check, inside the transaction: the applied
                     ;; cards must equal what a re-split of the written body
                     ;; yields. This catches every way a scoped delta can
                     ;; under-describe the file -- a multi-match remove, a
                     ;; replacement whose unclosed code fence swallows the
                     ;; next delimiter on a whole-page parse, a wrong-target
                     ;; resolution. A drifting delta ABORTS wholesale (the
                     ;; bad state never commits) and falls back to a full
                     ;; re-parse.
                                 (if (not= (vec (parsing/split-by-hyphens body))
                                           (card-texts tx-handle page-name))
                                   (throw (ex-info "delta drifts from file" {::fallback true}))
                                   old-hashes))))))
           (catch clojure.lang.ExceptionInfo e
             (if (::fallback (ex-data e))
               ::fallback
               (throw e))))]
     (cond
       (= ::fallback result) (index-page! index page-store page-name body)
       ;; a reorder (nil result) moves no text: nothing to reconcile
       (some? result) (reconcile/notify! (:notify$ index)
                                         (when-let [card (:card delta)]
                                           [(str (:hash card))])
                                         result)))))

(defn unindex-page!
  "Removes a page and its cards from the index (page deleted or renamed
  away). The retraction and the body delete commit atomically."
  [index page-name]
  (let [hashes
        (commit-core! index
                      (fn [{tconn :conn tkv :kv}]
                        (let [db (d/db tconn)
                              peid (page-eid db page-name)
                              hashes (mapv (fn [[_idx _eid h]] h) (card-rows db page-name))
                              eids (cond-> (vec (card-eids db page-name))
                                     peid (conj peid))]
                          (when (seq eids)
                            (d/transact! tconn (mapv (fn [eid] [:db.fn/retractEntity eid]) eids)))
                          (page-body/del! tkv page-name)
                          hashes)))]
    (reconcile/notify! (:notify$ index) nil hashes)))

(defn build!
  "Startup indexer: indexes every page in the page-store. Pages are
  transacted in batches: LMDB caps how much one transaction may dirty,
  so a single transaction over a whole wiki of card entities fails with
  MDB_PAGE_FULL. For the same reason the batches deliberately do NOT go
  through commit-core! -- folding bodies and texts into the datom
  transactions would grow them severalfold, and build! needs no
  atomicity: it runs at boot before any reader exists, over a scratch
  env a failed boot discards wholesale. The search docs are reconciled
  synchronously at the end -- boot completes with search fully
  populated; only runtime writes defer to the reconcile process.
  Returns the index handle."
  [{:keys [conn kv] :as index} page-store]
  (doseq [batch (partition-all 64 (.page-names page-store))]
    (let [pages (mapv (fn [page-name]
                        (let [body (.load-page page-store page-name)]
                          [page-name body (parsing/raw-text->card-maps body)]))
                      batch)]
      (page-body/put! kv (mapv (fn [[page-name body _card-maps]] [page-name body]) pages))
      (card-text/put-new! kv (mapcat (fn [[_name _body card-maps]] card-maps) pages))
      (d/transact! conn (into []
                              (mapcat (fn [[page-name _body card-maps]]
                                        (page-tx-maps page-store page-name card-maps)))
                              pages))))
  (reconcile/reconcile! index nil)

  index)

;; Reading

(defn page-names [{:keys [conn]}]
  (sort (d/q '[:find [?name ...]
               :where [_ :page/name ?name]]
             (d/db conn))))

(defn page-exists? [{:keys [conn]} page-name]
  (some? (page-eid (d/db conn) page-name)))

(defn page-body
  "The page's byte-exact body, or nil when the page is not indexed. The
  Datalog side is the listing authority: gating on it keeps a body blob
  a failed unindex left behind from resurrecting a deleted page (callers
  like append-to-new-page! treat a readable body as page-exists)."
  [{:keys [conn kv]} page-name]
  (when (page-eid (d/db conn) page-name)
    (page-body/body kv page-name)))

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
  [index page-name]
  (map parsing/raw-card-text->card-map (card-texts index page-name)))

(defn lookup-card
  "The card in page-name whose content hash or declared :card/id matches
  hash-or-id, as [card-map locator] where locator is :hash or :id -- or
  nil. Resolution order mirrors cards/card-match over the parsed card
  list: the earliest matching card wins whether it matched by hash or by
  id (see target-card)."
  [{:keys [conn kv]} page-name hash-or-id]
  (let [db (d/db conn)]
    ;; resolve to an eid first, then fetch the one card's text by its
    ;; hash: fetching texts inside the join would drag every card on the
    ;; page along
    (when-let [[_idx eid locator] (target-card db page-name hash-or-id)]
      [(-> (d/pull db [:card/hash] eid)
           (:card/hash)
           (->> (card-text/text kv))
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
  [{:keys [conn kv]}]
  (->> (d/q '[:find ?name ?idx ?h
              :where [?c :card/deadline? true]
              [?c :card/idx ?idx]
              [?c :card/hash ?h]
              [?c :card/page ?p]
              [?p :page/name ?name]]
            (d/db conn))
       (sort-by (fn [[name idx _h]] [name idx]))
       (map (fn [[name _idx h]] [name (card-text/text kv h)]))))

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
  [{:keys [conn kv] :as index}]
  (->> (d/q '[:find ?pname ?from ?h
              :where [?c :card/transcludes-from ?from]
              [?c :card/hash ?h]
              [?c :card/page ?p]
              [?p :page/name ?pname]]
            (d/db conn))
       (keep (fn [[pname from h]]
               (if-not (page-exists? index from)
                 {:page pname :from from :missing-page true}
                 (let [ids (:ids (parsing/card-map->card-data
                                  (parsing/raw-card-text->card-map (card-text/text kv h))))]
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
  higher. Hits are content-addressed hashes from the bc-cards engine,
  resolved to the pages currently carrying them -- a document orphaned
  by a missed removal resolves to no page and drops out here."
  [{:keys [conn engine]} query]
  (let [db (d/db conn)
        ;; realized inside the lock: d/search is lazy and the engine's
        ;; structures are not safe against a concurrent writer's doc sync
        hits (locking engine (vec (d/search engine query)))]
    (->> hits
         (mapcat (fn [h]
                   (sort (d/q '[:find [?name ...]
                                :in $ ?h
                                :where [?c :card/hash ?h]
                                [?c :card/page ?p]
                                [?p :page/name ?name]]
                              db h))))
         (distinct))))
