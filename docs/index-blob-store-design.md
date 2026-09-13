# Index Blob Store — Design

Status: COMPLETE — all four steps implemented 2026-09-12. The Datalog
schema is structural-only (names, hashes, ids, links, flags, instants);
no datom value can approach the 497-byte giant threshold, so the giant
allocator is never invoked. Motivated by the giant-ID incidents
(see Motivation) but worth doing on workload-fit grounds even after the
upstream fix ships.

Revision (step 1): Datalevin 1.0.2 provides `d/datalog-kv` — the
documented way to open application KV dbis in the *same env* as the
Datalog store. Decision 2's second scratch env is unnecessary: the blobs
live beside the Datalog indexes, one dir, one lifecycle (the KV handle
is owned by the conn; `close!` is unchanged). The handle is
`{:conn :kv :dir}`, growing `:engine` in step 2 (the standalone search
engine should likewise be attachable to the same env via the
`datalog-kv` handle — verify alongside the step-2 open questions).

## Goal

Keep large text (card source, page bodies) out of Datalog datom values so
the index never allocates "giants", while preserving every read, write,
and search behavior of the current index. Everything stays inside
Datalevin's public API — this is not a raw-LMDB side-step; it moves data
between the three APIs Datalevin ships (Datalog, KV, standalone search)
to put each kind of data in the layer whose shape matches it.

## Motivation

Datalevin's Datalog layer encodes each datom's value into the LMDB *key*
of the EAV/AVE indices (sorted covering indexes). LMDB keys are capped at
511 bytes, so values over 497 bytes are truncated in-key and the full
value moves to a `datalevin/giants` side table under an allocated giant
ID. BC is an outlier tenant for that design: multi-KB `:card/text` and
`:page/body` values with high churn mean nearly every card write
exercises the giants path.

That path bit us twice:

- `MDB_PAGE_FULL` storing multi-KB blobs in an untyped attr during the
  original corpus indexing (worked around: batched `build!`, re-derive
  card maps from `:card/text`).
- Production `MDB_PROBLEM: txn should abort` + sticky
  `Document does not exist {:doc-ref [:g ...]}` (3 incidents through
  2026-09-12): a giant-ID allocator race, fixed upstream in datalevin
  commit `8794caac2114a1a62436ccdb99a6c7d840b9a899` ("refresh giant id
  floor in transaction"), unreleased as of 2026-09-12 (expected in
  1.2.0).

The upstream fix addresses the known defect, but the workload mismatch
remains: the giants path is the least-traveled code in Datalevin and BC
lives on it. Datalevin's KV API has no such limit — keys stay small
(page names, hashes) and text goes in LMDB *values* (up to ~4GB, native
overflow pages, the same battle-tested mechanism as SQLite overflow /
Postgres TOAST). After this change every remaining datom value is a page
name, a 36-char hash string, a pr-str'd id, or a boolean: the giant
allocator is simply never invoked.

## Decisions

1. **Datalog keeps structure only.** `:page/name`, `:page/last-modified`,
   `:card/page`, `:card/idx`, `:card/hash`, `:card/id`, `:card/links`,
   `:card/transcludes-from`, `:card/deadline?` are unchanged.
   `:page/body` and `:card/text` (and with it `:db/fulltext` and the
   `:search-domains` conn opt) are dropped from the schema.
2. **Blobs live in KV dbis beside the Datalog indexes** (same env, via
   `d/datalog-kv` — public API; see the revision note above):
   - `card-text`: `hash-string → card source text`. Content-addressed:
     `:card/hash` is `(util/hash-it source-body)` (parsing.clj), a pure
     function of the text, and the delta machinery already treats
     equal-hash cards as interchangeable identical content. Writes are
     put-if-absent; duplicate-hash cards share one blob; reorders touch
     nothing.
   - `page-body`: `page-name → byte-exact body` (what `load-page`
     serves).
3. **Fulltext moves to the standalone search engine**
   (`d/new-search-engine` on the KV env). Documents are keyed by hash,
   content-addressed like the blobs:
   - `add-doc hash text` when a hash first appears anywhere in the wiki.
   - `remove-doc hash` only when a post-tx Datalog query shows no card
     still carries it. A skipped/failed removal is harmless (see 4).
   - `search-pages` becomes: `d/search` → ranked hashes → Datalog join
     hash→page-names, order-preserving, distinct. Orphaned doc hashes
     resolve to zero pages and drop out at query time; restart GCs.
4. **One transactional core; search outside it.** (REVISED 2026-09-12 —
   originally "no cross-store transaction; ordering is the consistency
   story", implemented as blob-puts → add-docs → Datalog tx with mtime
   as commit marker → remove-docs.) Verified by experiment on 1.0.2:
   `d/with-transaction` + the transaction conn's `datalog-kv` handle
   compose Datalog and KV writes into ONE LMDB write transaction with
   atomic commit, atomic abort, and reader isolation. `commit-core!`
   wraps every mutator: datoms, page body, and card-text blobs commit
   or abort as a unit — no ordering protocol, no commit marker, no
   mixed intermediate states. The search engine must never be written
   inside the transaction: its in-memory index does not participate in
   aborts (an enrolled engine survives rollback in divergent form — the
   production `Document does not exist` aftermath). Engine sync
   (`sync-card-docs!`) runs strictly post-commit and is non-fatal:
   search is the eventually consistent tier, and its staleness is
   one-sided — hits are resolved against the committed core at query
   time, so a lagging engine can only miss fresh edits, never return
   pages for content they no longer contain. Read-after-write for the
   document workflow (edit → POST → GET) is a hard invariant, enforced
   by the synchronous core commit in the request path; eventual
   consistency is scoped to full-text search alone.
   `index-card-delta!`'s drift invariant now checks inside the
   transaction: a delta that under-describes the file ABORTS (the bad
   state never commits) and falls back to a full re-parse. `build!`
   deliberately stays outside `commit-core!` (boot-time, no readers,
   scratch env; folding bodies+texts into the datom batches would
   stress the MDB_PAGE_FULL transaction-size cap).

   REVISED again (2026-09-12, second pass): engine sync left the write
   path entirely. Mutators notify a reconciliation process
   (`wiki.bc.storage.reconcile`, which owns the convergence algorithm
   -- reconcile! derives the desired document set from :card/hash
   datoms and diffs it against the engine, receiving only plain store
   handles {:conn :kv :engine}. Both blob stores are their own leaf
   namespaces -- `wiki.bc.storage.card-text` and
   `wiki.bc.storage.page-body`, each owning its dbi name, encoding,
   and read/write fns -- so index and reconcile depend on them without
   depending on each other; extracting card-text broke the would-be
   require cycle that a function-injection workaround had been
   papering over, and page-body followed for symmetry. Packaged in the
   client
   processes' idiom: `create-reconcile-process` wires a notifications
   channel into the sink on one platform thread; events may carry a
   per-event out-chan closed when handled; shutdown is closure
   propagation -- `close!` closes the intake and awaits the process's
   completion channel; the channels live on the index handle as
   `:notify$`/`:reconcile$`) and never wait on it. Reconciliation is two-speed
   (revised after the second review's O(cards)-per-write watch item):
   the per-wakeup fast path works from hints -- each write notifies
   the hashes it added and the hashes it may have orphaned, O(change)
   -- while the full derive (the desired document set is exactly
   (distinct :card/hash) ⨝ card-text dbi, recomputed from committed
   state) is the backstop, run every full-pass-interval wakeups and on
   the pass after a failure, so anything the hints missed is repaired
   within a bounded number of write bursts. Hints are an optimization,
   never a correctness dependency; a lost orphan candidate degrades to
   an orphaned document, the accepted garbage class. `build!` reconciles synchronously at the end, so boot
   completes with search fully populated; `await-search-sync!` is the
   barrier for tests (the former "searchable immediately" test now
   awaits reconciliation -- immediacy is explicitly not part of
   search's contract).
5. **Analyzer: same CamelCase pipeline, hopefully as plain fns.** The
   sci `inter-fn` requirement exists only because `get-conn`
   nippy-freezes conn opts into LMDB; a standalone engine takes its
   analyzer at open time each boot. Verify with a boot smoke test; if
   freezing still bites, the existing inter-fn pipeline keeps working.
6. **Handle shape**: `open-index` returns `{:conn :kv :dir}` (adding
   `:engine` in step 2). Every fn in `index.clj` already takes the
   handle map, so `index_db.clj`, `IndexedPageStore`, and both protocols
   keep their signatures.

## Path changes

Writes (`index-page!`, `index-card-delta!`):

- Full reindex: split cards → tx-maps minus `:card/text`/`:page/body` →
  ordering per Decision 4. Add/remove doc sets come from diffing the
  page's pre-tx card hashes (from `card-rows`) against the new ones,
  with removals checked against global references.
- `:replace` / `:append` delta: one blob put, one `add-doc`, one small
  tx, maybe one `remove-doc`.
- `:remove` delta: tx, maybe `remove-doc`.
- `:reorder` delta: Datalog-only — no blob or doc ops at all (today it
  rewrites `:page/body`, a giant, on every reorder).
- The post-write invariant check (split-compare) reads card texts via
  hash → KV instead of `:card/text`.

Reads:

- `page-body` → KV get.
- `card-texts`, `page-cards`, `lookup-card`, `deadline-cards`,
  `broken-transclusions` → `(idx, hash)` from Datalog as today, then KV
  get per text (same-process LMDB gets, microseconds).
- Link graph (`all-links`, `links-to`, `broken-links`, `orphan-pages`,
  `transcluded-into`): untouched.

## Expected wins

- The giants machinery goes from "every card write" to "never" — this
  bug class cannot reach BC regardless of upstream state.
- Datalog transactions shrink dramatically (today each save transacts
  the page content roughly twice over as giants: `:page/body` plus every
  `:card/text`). `build!`'s MDB_PAGE_FULL headroom grows; batch size can
  likely increase.
- Dropping the sci analyzer (if Decision 5 verifies) removes the
  ~3ms/page interpreter cost at boot indexing and per query term.

## Costs

- Three stores kept loosely consistent instead of one transaction —
  mitigated by Decision 4's ordering plus disposability.
- Equivalence tests must widen: "delta result == fresh rebuild" should
  compare KV contents and search results (probe queries), not just
  datoms.
- Blob garbage (`card-text` entries no card references) accumulates
  until restart — bounded by one session's edit volume; eager
  `remove-doc` keeps the *search* side clean.

## Open questions

1. Plain-fn analyzer accepted by the standalone engine? Source-verified
   (the engine keeps the analyzer as an in-memory field; no opts
   freeze), but step 2 deliberately reuses the existing inter-fn
   `page-analyzer` so both domains tokenize identically for parity.
   Swap to `datalevin.analyzer` plain fns in step 3+, when the
   "datalevin" domain (whose frozen conn opts force inter-fns) retires.
2. RESOLVED (step 2 tests): `add-doc` upserts on an existing doc-ref;
   writes guard with `doc-indexed?` anyway since a content-addressed
   re-add is identical by construction.
3. RESOLVED (step 2 tests): `d/search` ranking matches
   `d/fulltext-datoms` on all probe queries (both paths share the
   engine's `:top 10` default; blank queries return empty on both).

## Migration steps (each independently shippable)

1. `:page/body` → KV `bc/page-body` dbi. Mechanical; kills the
   second-biggest giant source; fulltext untouched. DONE 2026-09-12:
   dbi keyed/valued `:string`/`:string`; writes are body-first then
   Datalog tx (mtime as commit marker, per Decision 4); `unindex-page!`
   retracts first then deletes the body (fails toward an unlisted stale
   blob, not a listed page with no body — `load-page` treats nil body
   as page-missing).
2. Standalone engine dual-written alongside `:db/fulltext`; parity
   asserted in tests. DONE 2026-09-12: engine in domain `bc-cards` on
   the shared env, docs content-addressed by hash (add guarded by
   `doc-indexed?`, removal by a post-tx wiki-wide reference check;
   reorders skip doc sync entirely). `search-pages-engine` is the
   dual-write twin of `search-pages`. Every delta-equivalence test now
   also compares doc-count + indexed-hash coverage against a rebuild,
   and dedicated tests assert query-result parity across build /
   re-index / unindex plus shared-doc lifecycle for duplicate cards.
3. Flip `search-pages` to the engine; drop `:db/fulltext` from
   `:card/text`. DONE 2026-09-12: `search-pages` is the former
   `search-pages-engine` (parity test retired with the datom path; the
   behavior tests are the spec and passed unchanged); the
   `:search-domains` conn opt is gone, and with nothing frozen into
   conn opts anymore the analyzer pipeline moved from sci inter-fns to
   `datalevin.analyzer`'s plain compiled fns (open question 1 resolved:
   identical tokenization, no interpreter cost per token).
4. `:card/text` → KV `bc/card-text` dbi; rewrite the readers; drop the
   attribute. DONE 2026-09-12: blobs and search docs share the hash key
   (`add-card-content!`: text lands before the doc, with the doc as the
   idempotency guard). Readers (`card-texts`, `lookup-card`,
   `deadline-cards`, `broken-transclusions`) resolve hash → KV. A live
   smoke run (boot over bedrock, page read, save, replacecard, search
   both directions) verified the full write→search cycle. Post-review
   (below): the sweep is docs-only — card-text blobs are append-only at
   runtime, exactly the garbage budget in Costs.

## Adversarial review (2026-09-12)

Two independent adversarial reviews of the branch (correctness/races
and behavioral equivalence) produced four accepted findings, fixed the
same day:

1. **Eager blob deletion raced unlocked readers** (confirmed by repro):
   readers resolve card hashes from an immutable Datalog snapshot but
   fetch texts from the live KV, so a writer's sweep could delete a
   blob under a reader's snapshot → nil text → NPE/500. Fix: the sweep
   (`remove-stale-card-docs!`) no longer touches blobs — `bc/card-text`
   is append-only until the boot rebuild. Orphaned docs still resolve
   to no page at the search join; orphaned blobs only cost scratch
   space.
2. **Engine calls unsynchronized**: datalevin 1.0.2's `remove-doc` and
   `search` take no lock and mutate/read shared in-memory structures.
   Fix: all engine calls in index.clj serialize on the engine object,
   and `search-pages` realizes the lazy hit seq inside the lock.
3. **A sweep failure 500'd an already-committed write** (and the
   committed mtime marker meant it never re-healed). Fix: the sweep is
   try/logged GC, not part of the write contract.
4. **`page-body` served blobs without a Datalog existence check**, so a
   failed `unindex-page!` body-delete could resurrect a deleted page's
   content via `append-to-new-page!`. Fix: `page-body` is gated on the
   Datalog side (the listing authority).

One accepted behavioral divergence (design-doc claim corrected, not a
bug): with per-distinct-text documents, duplicate card texts no longer
occupy multiple slots of the engine's top-10 document window — a page
with N identical matching cards used to crowd out other matches and
rank first; now it holds one slot and other carriers appear. Search
membership can grow, never shrink, and dedup frees result slots —
accepted as an improvement and pinned by test
(`search-dedups-duplicate-texts-across-the-ranking-window`).

### Second review round (post-transactional-core + reconcile process)

A second pair of adversarial reviews over the finished tree confirmed
the architecture (acyclic lock-order graph across the write-lock, the
engine monitor, and the LMDB writer mutex; abort/::fallback semantics
sound including datalevin's transparent retry-on-resize; all-or-nothing
heal windows; letter-faithful store extractions; complete test
barriers) and produced a fix set around the process's failure modes,
applied the same day:

1. **Process death**: the loop caught Exception only; an Error killed
   the thread silently, after which the caller-side `a/put!` would
   eventually throw into committed writes (~1088 notifications later).
   Fixed: the loop catches Throwable and continues; `notify!` treats a
   saturated channel as a dropped notification (warn) -- harmless by
   design -- instead of throwing out of a committed write.
2. **close! timeout**: proceeding to `d/close` + dir delete under a
   still-running process thread is native-UB territory, and close!
   runs in prod shutdown, not just dev reload. Fixed: on timeout the
   scratch env is deliberately leaked (warn) rather than closed under
   a live thread.
3. **Coalescing**: each wakeup now drains all pending notifications
   and runs ONE covering pass (derived reconciliation makes this
   exactly equivalent), removing the quadratic bulk-write backlog that
   made 1 and 2 reachable.
4. Honesty/observability: `await!` returns false on a closed channel;
   an indexed hash with no stored text (atomic-commit invariant
   violation, currently unreachable) is warned instead of silently
   skipped forever; fixture awaits are wrapped in `is`.
5. New tests: reconcile failure isolation (throwing pass is survived,
   barrier releases, next pass converges), card-text write-once
   (existing hash never overwritten), close!-drains-queued (closure
   propagation before env close).

Watch item RESOLVED (hints fast path, chosen over lock-window
shrinking and in-sync probes): a runtime write now costs an O(change)
hinted pass; the O(total-cards) derive runs only on the backstop
cadence (every full-pass-interval wakeups, or the pass after a
failure) and at boot. The failure-isolation test pins the
suspicious-pass backstop: a failed pass's lost hints are repaired by
the next pass even when triggered by an unrelated write.

### Third review round (two-speed reconciler)

Two adversarial reviews of the two-speed redesign confirmed the
convergence invariant by construction and by experiment: the
until-full state machine is exact (full passes at wakeups 32/64,
failure→full-until-success, correctly phased against boot), hint sets
are complete for every mutator path with no failure involved, the
saturation catch matches core.async's actual throw, and the close!
leak branch is benign. No correctness findings. Applied from the
review:

- Docstrings corrected: the backstop repairs lost ADD hints only.
  Removal is candidate-driven on both speeds, so a lost orphan
  candidate's document lingers until reboot — never SURFACED (the
  query-time join filters it; staleness stays one-sided) but occupying
  ranked-document slots in search's top-N window, i.e. possible false
  negatives at the results tail under accumulated failures/drops,
  bounded per process lifetime. Deriving removals in the backstop was
  considered and declined: it requires scanning the engine's internal
  docs dbi (no public enumeration API), coupling to datalevin's
  layout for a garbage class reboot already clears.
- The backstop cadence is testable and tested:
  `create-reconcile-process` takes an optional `:full-pass-interval`
  (default 32); a test at interval 2 commits content with every
  notification suppressed and proves the interval's full pass makes it
  searchable — the dropped-notification repair path, pinned.
- Declined: in-batch dedupe in card-text `put-new!` (unreachable with
  honest hashes). Recorded as INFO, bounded, no fix: a persistent
  fault pins every wakeup to the full pass until one succeeds (no
  livelock — wakes only on writes, partial progress sticks, and no
  deterministic poison is constructible since datalevin drops
  oversized terms upstream).

Known residuals, accepted: a crash between the body KV put and the
Datalog tx leaves body/cards mixed for that page until its next write
(reads never heal, only writes) — ELIMINATED by the transactional core
(Decision 4 revision): that intermediate state can no longer exist.
Remaining: `close!` during in-flight reads is a dev-reload-only hazard,
bounded by the disposable-index design.

Consensus additions (2026-09-12 discussion): soft-delete/sweep of
orphaned card-text blobs is NOT needed — orphans are unreachable by
reads (every path joins through datoms), immune to poisoning
(content-addressed, write-once), and reclaimed at boot; it stays a
documented optimization (transactional tombstone + grace-period sweep)
to be revisited only on evidence of growth. Note the scratch env lives
under java.io.tmpdir, which Debian 13 mounts as tmpfs — index size
(orphans included) is RAM-backed there; relocating the scratch dir is
the cheap knob if that ever matters (decision: not doing it now).

Each step boots into a fresh rebuild (schema changes are free by
design) and is independently revertable.
