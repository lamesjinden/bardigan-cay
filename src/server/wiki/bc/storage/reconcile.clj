(ns wiki.bc.storage.reconcile
  "Search-index reconciliation: converges the search engine onto the
  committed core state.

  Two-speed convergence. The fast path per wakeup works from hints:
  each write notifies the hashes it added (documents ensured, texts
  fetched from the card-text store -- datoms and texts commit
  atomically, so a hinted hash always has its text) and the hashes it
  may have orphaned (documents removed when no card still references
  them). The backstop is the full derive -- every distinct :card/hash
  datom must have a document -- run every full-pass-interval wakeups
  and immediately after a failed pass, so lost ADD hints (a dropped
  notification, a failed pass) are repaired within a bounded number of
  write bursts. Lost ORPHAN candidates are not: removal is
  candidate-driven on both speeds, so their documents linger until the
  boot rebuild -- never surfaced (query-time hits resolve against the
  core) but occupying ranked-document slots in search's top-N window.
  Reconciling adds from scratch is always correct; the hints are an
  optimization, never a correctness dependency.

  The process packaging follows the client processes' idiom
  (wiki.bc.async / wiki.bc.request-process): a constructor wires a
  source channel into the sink, an event may carry a per-event out-chan
  closed once it has been handled, and shutdown is closure propagation
  -- closing the source drains and ends the process. The channel is the
  interface; there is no service handle. Unlike the client's go-loop
  pipelines the sink runs on a dedicated platform thread: reconciling
  performs LMDB writes, which block and require stable OS-thread
  identity."
  (:require [clojure.core.async :as a]
            [datalevin.core :as d]
            [taoensso.timbre :refer [warn]]
            [wiki.bc.storage.card-text :as card-text]))

(defn- add-missing-docs!
  "Ensures a search document exists for each of hashes, fetching texts
  from the card-text store. Engine calls serialize on the engine
  object: datalevin 1.0.2's remove-doc and search take no internal
  lock."
  [{:keys [kv engine]} hashes]
  (let [missing (locking engine
                  (filterv (fn [h] (not (d/doc-indexed? engine h)))
                           (vec (distinct hashes))))
        texts (mapv (fn [h] [h (card-text/text kv h)]) missing)
        textless (filterv (fn [[_h text]] (nil? text)) texts)
        additions (filterv (fn [[_h text]] (some? text)) texts)]
    ;; an indexed hash without stored text violates the atomic-commit
    ;; invariant; it cannot self-heal (the doc is skipped every pass),
    ;; so it must at least self-report
    (when (seq textless)
      (warn "card hashes indexed without stored text; their documents are skipped:"
            (mapv first textless)))
    (locking engine
      (doseq [[h text] additions]
        ;; check-exist? false: the missing filter above is that check
        (d/add-doc engine h text false)))))

(defn- remove-stale-docs!
  "Removes the documents among orphan-candidates that no card in the
  index references any longer (documents are content-addressed and
  shared across pages, so removal checks wiki-wide)."
  [{:keys [conn engine]} orphan-candidates]
  (let [db (d/db conn)
        stale (->> (distinct orphan-candidates)
                   (filterv (fn [h]
                              (nil? (d/q '[:find ?c .
                                           :in $ ?h
                                           :where [?c :card/hash ?h]]
                                         db h)))))]
    (locking engine
      (doseq [h stale]
        (when (d/doc-indexed? engine h)
          (d/remove-doc engine h))))))

(defn reconcile!
  "The full backstop pass: converges the search engine onto the whole
  committed core state -- a document for every distinct :card/hash
  datom, orphan-candidates' documents removed when unreferenced.
  O(total cards); the process runs it on the backstop cadence, and
  build! runs it synchronously at boot.

  stores holds the handles converged between: {:conn <Datalog conn>
  :kv <KV handle for the card-text store> :engine <search engine>}."
  [{:keys [conn] :as stores} orphan-candidates]
  (add-missing-docs! stores (d/q '[:find [?h ...] :where [_ :card/hash ?h]]
                                 (d/db conn)))
  (remove-stale-docs! stores orphan-candidates))

(defn- reconcile-hinted!
  "The fast path: converges only the hinted hashes -- O(change), not
  O(wiki)."
  [stores added-hashes orphan-candidates]
  (add-missing-docs! stores added-hashes)
  (remove-stale-docs! stores orphan-candidates))

(def ^:private default-full-pass-interval
  "Every Nth wakeup runs the full backstop derive instead of the hinted
  fast path, bounding the repair latency of lost add hints to N write
  bursts."
  32)

(defn- drain-pending
  "Every event immediately available on notifications$, without
  blocking."
  [notifications$]
  (loop [events []]
    (if-some [event (a/poll! notifications$)]
      (recur (conj events event))
      events)))

(defn create-reconcile-process
  "Wires notifications$ into reconciliation over stores, on a dedicated
  platform thread. An event is
  {:added [hash ...] :orphan-candidates [hash ...]} and may carry
  :out-chan, closed once the event has been handled.

  Each wakeup drains everything pending and runs ONE pass covering the
  union, so a write burst costs one pass instead of quadratic work.
  Most passes take the hinted fast path; the full backstop derive runs
  every :full-pass-interval wakeups (optional, default
  default-full-pass-interval) and on the pass after a failure (whose
  hints were lost). Failures -- Throwable, not just Exception: a
  silently dead process would eventually saturate the channel and fail
  writes -- are logged and the process continues; search is the
  eventually consistent tier, and nothing in the write path waits on
  it. Closing notifications$ ends the process after it drains what it
  already accepted; returns the process's completion channel."
  [notifications$ stores & {:keys [full-pass-interval]
                            :or   {full-pass-interval default-full-pass-interval}}]
  (a/thread
    (loop [until-full (dec full-pass-interval)]
      (when-some [event (a/<!! notifications$)]
        (let [events (into [event] (drain-pending notifications$))
              orphan-candidates (mapcat :orphan-candidates events)
              full? (zero? until-full)
              ok? (try
                    (if full?
                      (reconcile! stores orphan-candidates)
                      (reconcile-hinted! stores
                                         (mapcat :added events)
                                         orphan-candidates))
                    true
                    (catch Throwable t
                      (warn "search reconcile failed (next pass runs the full backstop):"
                            (ex-message t))
                      false))]
          (doseq [{:keys [out-chan]} events]
            (when out-chan
              (a/close! out-chan)))
          (recur (cond
                   (not ok?) 0
                   full? (dec full-pass-interval)
                   :else (dec until-full))))))))

(defn notify!
  "Puts a notification onto notifications$: added-hashes are the card
  hashes the caller's write introduced (the fast path's add hints),
  orphan-candidates the hashes it may have orphaned. Non-blocking; a
  no-op once the channel is closed. A saturated channel (dead or deeply
  backlogged process) degrades to DROPPING the notification rather than
  throwing out of the caller's already-committed write: the dropped add
  hints are repaired by the backstop pass; the dropped orphan
  candidates leave orphaned documents until the boot rebuild (never
  surfaced -- query-time hits resolve against the core)."
  [notifications$ added-hashes orphan-candidates]
  (try
    (a/put! notifications$ {:added             added-hashes
                            :orphan-candidates orphan-candidates})
    (catch AssertionError e
      (warn "search notification dropped (reconcile process saturated or dead):"
            (ex-message e)))))

(defn await!
  "Blocks until every notification put before this call has been
  handled, or timeout-ms elapses; true on completion. False on timeout
  or when notifications$ is already closed (a barrier can no longer be
  guaranteed). Rides the per-event out-chan convention: the barrier
  event's out-chan closes when the process reaches it."
  [notifications$ timeout-ms]
  (let [out-chan (a/chan)]
    (if (a/put! notifications$ {:out-chan out-chan})
      (let [[_ port] (a/alts!! [out-chan (a/timeout timeout-ms)])]
        (= port out-chan))
      false)))
