(ns wiki.bc.storage.index-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [wiki.bc.cards.cards :as cards]
            [wiki.bc.cards.parsing :as parsing]
            [wiki.bc.storage.index :as index]
            [wiki.bc.storage.page-store :as pagestore]
            [wiki.bc.test-fixtures :refer [temp-wiki-dir build-index await-search-sync!]]))

(defn- indexed-names [{:keys [conn]}]
  (sort (d/q '[:find [?n ...] :where [?e :page/name ?n]] (d/db conn))))

(defn- indexed-links [{:keys [conn]}]
  (d/q '[:find ?n ?t
         :where [?p :page/name ?n] [?c :card/page ?p] [?c :card/links ?t]]
       (d/db conn)))

(defn- card-entity-count [{:keys [conn]}]
  (count (d/q '[:find ?c :where [?c :card/idx _]] (d/db conn))))

(defn- fulltext-names [idx query]
  (sort (index/search-pages idx query)))

(defn- card-text-blob-count [{:keys [kv]}]
  (d/entries kv "bc/card-text"))

(deftest duplicate-card-texts-share-one-search-doc
  (let [dir (temp-wiki-dir {"A" "shared zebra card"
                            "B" "shared zebra card"})
        idx (build-index dir)]
    (try
      (is (= 1 (d/doc-count (:engine idx))))
      (is (= 1 (card-text-blob-count idx)))
      (is (= #{"A" "B"} (set (index/search-pages idx "zebra"))))
      (testing "unindexing one page keeps the shared doc for the other"
        (index/unindex-page! idx "A")
        (is (await-search-sync! idx))
        (is (= ["B"] (vec (index/search-pages idx "zebra"))))
        (is (= 1 (d/doc-count (:engine idx)))))
      (testing "unindexing the last carrier removes the doc but never the blob:
                readers resolve hashes from Datalog snapshots against the live
                KV, so blobs must outlive every snapshot referencing them"
        (index/unindex-page! idx "B")
        (is (await-search-sync! idx))
        (is (= [] (vec (index/search-pages idx "zebra"))))
        (is (= 0 (d/doc-count (:engine idx))))
        (is (= 1 (card-text-blob-count idx))))
      (finally
        (index/close! idx)))))

(deftest failed-core-transaction-leaves-no-trace
  ;; the core commit (datoms + page body + card texts) is one LMDB write
  ;; transaction: a failure mid-write must roll back ALL of it. The
  ;; injected throw fires after the body and blob puts have executed --
  ;; under the pre-transactional ordering this exact scenario left the
  ;; new body beside the old cards.
  (let [dir (temp-wiki-dir {"P" "original armadillo text"})
        store (pagestore/make-page-store (str dir))
        idx (index/build! (index/open-index) store)
        state (fn [] {:body  (index/page-body idx "P")
                      :cards (vec (index/page-cards idx "P"))
                      :blobs (card-text-blob-count idx)
                      :docs  (d/doc-count (:engine idx))})
        before (state)]
    (try
      (with-redefs [wiki.bc.storage.index/page-tx-maps
                    (fn [& _] (throw (ex-info "injected tx failure" {})))]
        (is (thrown? Exception
                     (index/index-page! idx store "P" "replacement pangolin text"))))
      (is (= before (state)))
      (finally
        (index/close! idx)))))

(deftest blobs-survive-every-write-that-orphans-their-hash
  ;; pins the no-eager-blob-GC invariant that keeps concurrent readers
  ;; from resolving a snapshot's hash to a deleted blob
  (let [dir (temp-wiki-dir {"P" "original wombat text"})
        store (pagestore/make-page-store (str dir))
        idx (index/build! (index/open-index) store)]
    (try
      (spit (io/file dir "P.md") "replacement numbat text")
      (index/index-page! idx store "P")
      (is (await-search-sync! idx))
      (is (= 2 (card-text-blob-count idx)) "orphaned blob retained")
      (is (= 1 (d/doc-count (:engine idx))) "orphaned doc removed")
      (is (= [] (vec (index/search-pages idx "wombat"))))
      (is (= ["P"] (vec (index/search-pages idx "numbat"))))
      (finally
        (index/close! idx)))))

(deftest search-dedups-duplicate-texts-across-the-ranking-window
  ;; pins the content-addressed semantics: one document per distinct
  ;; text, however many pages or cards carry it, so duplicates cannot
  ;; crowd other matches out of the engine's top-10 document window
  (let [shared "the elusive quokka appears"
        pages (into {"Solo" "a different quokka sighting"}
                    (map (fn [i] [(format "Dup%02d" i) shared]))
                    (range 12))
        dir (temp-wiki-dir pages)
        idx (build-index dir)]
    (try
      (is (= 2 (d/doc-count (:engine idx))))
      (let [results (vec (index/search-pages idx "quokka"))]
        (is (= 13 (count results)) "all carriers returned, none cut by :top")
        (is (= (sort (keys pages)) (sort results)))
        (is (apply distinct? results)))
      (finally
        (index/close! idx)))))

(deftest empty-page-bodies-round-trip
  ;; the KV body store must distinguish a stored "" from nil-means-missing:
  ;; load-page treats nil as page-missing
  (let [dir (temp-wiki-dir {"Empty" ""
                            "Full"  "some content"})
        store (pagestore/make-page-store (str dir))
        idx (index/build! (index/open-index) store)]
    (try
      (is (index/page-exists? idx "Empty"))
      (is (= "" (index/page-body idx "Empty")))
      (is (= [] (vec (index/page-cards idx "Empty"))))
      (testing "a page emptied by a later write stays readable"
        (spit (io/file dir "Full.md") "")
        (index/index-page! idx store "Full")
        (is (index/page-exists? idx "Full"))
        (is (= "" (index/page-body idx "Full"))))
      (testing "an unindexed page's body reads nil"
        (index/unindex-page! idx "Empty")
        (is (nil? (index/page-body idx "Empty"))))
      (finally
        (index/close! idx)))))

(deftest search-pages-is-token-based-and-ranked
  (let [dir (temp-wiki-dir {"Both" "the quick brown fox"
                            "One"  "quick notes about deadline: dates"
                            "None" "nothing relevant"})
        idx (build-index dir)]
    (try
      (testing "matching is case-insensitive"
        (is (= #{"Both" "One"} (set (index/search-pages idx "QUICK")))))
      (testing "a multi-term query ranks the page matching more terms first"
        (is (= #{"Both" "One"} (set (index/search-pages idx "quick fox"))))
        (is (= "Both" (first (index/search-pages idx "quick fox")))))
      (testing "punctuation around a token does not block a match"
        (is (= ["One"] (vec (index/search-pages idx "deadline")))))
      (testing "tokens match whole words only"
        (is (= [] (vec (index/search-pages idx "quic")))))
      (testing "blank and no-hit queries return empty"
        (is (= [] (vec (index/search-pages idx ""))))
        (is (= [] (vec (index/search-pages idx "zebra")))))
      (finally
        (index/close! idx)))))

(deftest search-pages-splits-camel-case
  (let [dir (temp-wiki-dir {"Linker" "see [[HelloWorld]] for details"
                            "Plain"  "hello there"})
        idx (build-index dir)]
    (try
      (testing "a word inside a CamelCase wiki link is searchable"
        (is (= #{"Linker" "Plain"} (set (index/search-pages idx "hello")))))
      (testing "the whole CamelCase token still matches exactly"
        (is (= ["Linker"] (vec (index/search-pages idx "helloworld")))))
      (testing "a CamelCase query ranks the exact-token page first"
        (is (= "Linker" (first (index/search-pages idx "HelloWorld")))))
      (finally
        (index/close! idx)))))

(deftest build-indexes-every-page
  (let [dir (temp-wiki-dir {"Start" "welcome to [[About]] and [[Missing]]"
                            "About" "plain body, no links"})
        idx (build-index dir)]
    (try
      (testing "all pages present"
        (is (= ["About" "Start"] (indexed-names idx))))
      (testing "links recorded, including targets that do not exist"
        (is (= #{["Start" "About"] ["Start" "Missing"]} (indexed-links idx))))
      (testing "full-text search finds body content"
        (is (= ["About"] (fulltext-names idx "plain")))
        (is (= ["Start"] (fulltext-names idx "welcome"))))
      (testing "last-modified is stored as an instant"
        (is (inst? (d/q '[:find ?m . :where [?e :page/name "Start"] [?e :page/last-modified ?m]]
                        (d/db (:conn idx))))))
      (finally
        (index/close! idx)))))

(deftest index-page!-replaces-rather-than-accumulates
  (let [dir (temp-wiki-dir {"Start" "first version links [[Old]]"})
        page-store (pagestore/make-page-store (str dir))
        idx (index/build! (index/open-index) page-store)]
    (try
      (spit (io/file dir "Start.md") "second version links [[New]]")
      (index/index-page! idx page-store "Start")
      (is (await-search-sync! idx))
      (testing "old links are gone, new ones present"
        (is (= #{["Start" "New"]} (indexed-links idx))))
      (testing "full-text reflects the new body only"
        (is (= [] (fulltext-names idx "first")))
        (is (= ["Start"] (fulltext-names idx "second"))))
      (testing "still a single entity for the page"
        (is (= ["Start"] (indexed-names idx))))
      (testing "old card entities are replaced, not accumulated"
        (is (= 1 (card-entity-count idx)))
        (is (= ["second version links [[New]]"]
               (map :source_data (index/page-cards idx "Start")))))
      (finally
        (index/close! idx)))))

(deftest cards-are-indexed-with-the-page
  (let [source (str "intro card"
                    "\n\n----\n"
                    "{:card/type :markdown :card/id \"alpha\"}\n\nsecond card"
                    "\n\n----\n\n"
                    "third card")
        dir (temp-wiki-dir {"Multi" source})
        idx (build-index dir)]
    (try
      (testing "page-cards returns the parsed card maps in page order"
        (let [cards (index/page-cards idx "Multi")]
          (is (= 3 (count cards)))
          (is (= "intro card" (:source_data (first cards))))
          (is (= "third card" (:source_data (last cards))))))
      (testing "lookup by content hash reports the :hash locator"
        (let [intro (first (index/page-cards idx "Multi"))
              [card locator] (index/lookup-card idx "Multi" (str (:hash intro)))]
          (is (= "intro card" (:source_data card)))
          (is (= :hash locator))))
      (testing "lookup by declared :card/id reports the :id locator"
        (let [[card locator] (index/lookup-card idx "Multi" "alpha")]
          (is (string/ends-with? (:source_data card) "second card"))
          (is (= :id locator))))
      (testing "an unknown hash-or-id misses"
        (is (nil? (index/lookup-card idx "Multi" "no-such-card"))))
      (testing "a lookup against a page with no cards misses"
        (is (nil? (index/lookup-card idx "NoSuchPage" "alpha"))))
      (finally
        (index/close! idx)))))

(deftest index-page!-indexes-a-page-created-after-build
  (let [dir (temp-wiki-dir {"Start" "hello"})
        page-store (pagestore/make-page-store (str dir))
        idx (index/build! (index/open-index) page-store)]
    (try
      (spit (io/file dir "Later.md") "created after startup, links [[Start]]")
      (index/index-page! idx page-store "Later")
      (is (= ["Later" "Start"] (indexed-names idx)))
      (is (= #{["Later" "Start"]} (indexed-links idx)))
      (finally
        (index/close! idx)))))

(deftest unindex-page!-removes-the-page
  (let [dir (temp-wiki-dir {"Start" "links [[About]]"
                            "About" "about body"})
        idx (build-index dir)]
    (try
      (index/unindex-page! idx "About")
      (is (= ["Start"] (indexed-names idx)))
      ;; the body lives in the KV sub-database, not a datom, so retracting
      ;; the page entity alone would leave the page loadable
      (is (nil? (index/page-body idx "About")))
      (testing "unindexing an unknown page is a no-op"
        (index/unindex-page! idx "NeverExisted")
        (is (= ["Start"] (indexed-names idx))))
      (finally
        (index/close! idx)))))

(deftest close!-drains-queued-notifications
  ;; closure propagation: close! must let the process finish everything
  ;; it already accepted before the env closes. The dir-deleted
  ;; assertion doubles as the drain check -- the timeout branch leaks
  ;; the env instead of closing it, leaving the dir in place.
  (let [dir (temp-wiki-dir {"P" "revision zero"})
        store (pagestore/make-page-store (str dir))
        idx (build-index dir)]
    (dotimes [i 3]
      (spit (io/file dir "P.md") (str "revision " i))
      (index/index-page! idx store "P"))
    (index/close! idx)
    (is (not (.exists (io/file (:dir idx)))))))

(deftest close!-removes-the-scratch-directory
  (let [dir (temp-wiki-dir {"Start" "hello"})
        idx (build-index dir)]
    (is (.exists (io/file (:dir idx))))
    (index/close! idx)
    (is (not (.exists (io/file (:dir idx)))))))

;; region card-scoped deltas
;;
;; The invariant for index-card-delta!: after the file write and the
;; scoped transaction, the index must be indistinguishable from one
;; rebuilt from scratch over the same files -- whether the delta applied
;; card-scoped or fell back to a full re-parse.

(defn- indexed-transclusions [{:keys [conn]}]
  (d/q '[:find ?n ?t
         :where [?p :page/name ?n] [?c :card/page ?p] [?c :card/transcludes-from ?t]]
       (d/db conn)))

(defn- search-doc-state
  "Doc count plus which currently indexed card hashes have a search
  document. Delta-vs-rebuild equality of this map proves the dual-written
  engine tracked the datom state: a missing document shrinks :indexed, a
  leaked stale document inflates :count."
  [{:keys [conn engine]}]
  (let [hashes (d/q '[:find [?h ...] :where [_ :card/hash ?h]] (d/db conn))]
    {:count   (d/doc-count engine)
     :indexed (set (filter (fn [h] (d/doc-indexed? engine h)) hashes))}))

(defn- page-state [idx page-name]
  {:body          (index/page-body idx page-name)
   :cards         (mapv #(dissoc % :tx/locator) (index/page-cards idx page-name))
   :links         (indexed-links idx)
   :transclusions (indexed-transclusions idx)
   :deadlines     (vec (index/deadline-cards idx))
   :search-docs   (search-doc-state idx)})

(defn- rebuilt-state [dir page-name]
  (let [idx (build-index dir)]
    (try
      (page-state idx page-name)
      (finally
        (index/close! idx)))))

(def ^:private three-cards
  "card one links [[Alpha]]\n\n----\n\n{:card/type :markdown :card/id \"middle\"}\n\ncard two\n\n----\n\ncard three")

(defn- apply-delta-test
  "Builds a wiki of {page-name three-cards}, lets f produce
  [new-cards delta] from the current card list, writes the rebuilt page
  file (as the store's write path would), applies the delta, and checks
  the index against a full rebuild. Returns the resulting page state."
  [f]
  (let [dir (temp-wiki-dir {"P" three-cards})
        store (pagestore/make-page-store (str dir))
        idx (index/build! (index/open-index) store)]
    (try
      (let [[new-cards delta] (f (vec (index/page-cards idx "P")))]
        (spit (io/file dir "P.md") (cards/cards->raw new-cards))
        (index/index-card-delta! idx store "P" delta)
        (is (await-search-sync! idx))
        (let [state (page-state idx "P")]
          (is (= (rebuilt-state dir "P") state))
          state))
      (finally
        (index/close! idx)))))

(deftest delta-replace-matches-a-full-rebuild
  (let [new-card (parsing/raw-card-text->card-map "replacement links [[Beta]]")
        state (apply-delta-test
               (fn [cards]
                 [(assoc cards 1 new-card)
                  {:op :replace :target (str (:hash (second cards))) :card new-card}]))]
    (is (= "replacement links [[Beta]]" (:source_data (second (:cards state)))))
    (is (contains? (:links state) ["P" "Beta"]))))

(deftest delta-replace-by-declared-id-matches-a-full-rebuild
  (let [new-card (parsing/raw-card-text->card-map "replaced by id")]
    (apply-delta-test
     (fn [cards]
       [(assoc cards 1 new-card)
        {:op :replace :target "middle" :card new-card}]))))

(deftest delta-replace-with-splitting-text-falls-back
  ;; replacement text containing a bare delimiter re-parses as TWO cards;
  ;; the scoped path must refuse and the fallback re-parse must land it
  (let [new-card (parsing/raw-card-text->card-map "part one\n\n----\n\npart two")
        state (apply-delta-test
               (fn [cards]
                 [(assoc cards 1 new-card)
                  {:op :replace :target (str (:hash (second cards))) :card new-card}]))]
    (is (= 4 (count (:cards state))))))

(deftest delta-replace-of-unknown-target-falls-back
  (let [new-card (parsing/raw-card-text->card-map "orphan replacement")]
    ;; file gets the new card list but the delta names a target the index
    ;; does not know: fallback must reindex to match the file
    (apply-delta-test
     (fn [cards]
       [(assoc cards 1 new-card)
        {:op :replace :target "no-such-target" :card new-card}]))))

(deftest delta-remove-matches-a-full-rebuild
  (let [state (apply-delta-test
               (fn [cards]
                 [[(first cards) (last cards)]
                  {:op :remove :target (str (:hash (second cards)))}]))]
    (is (= 2 (count (:cards state))))
    (is (= ["card one links [[Alpha]]" "card three"]
           (mapv :source_data (:cards state))))))

(deftest delta-append-matches-a-full-rebuild
  (let [new-card (parsing/raw-card-text->card-map "appended card links [[Gamma]]")
        state (apply-delta-test
               (fn [cards]
                 [(conj cards new-card)
                  {:op :append :card new-card}]))]
    (is (= "appended card links [[Gamma]]" (:source_data (last (:cards state)))))
    (is (contains? (:links state) ["P" "Gamma"]))))

(deftest delta-reorder-matches-a-full-rebuild
  (let [state (apply-delta-test
               (fn [cards]
                 (let [new-cards [(last cards) (first cards) (second cards)]]
                   [new-cards
                    {:op :reorder :hashes (mapv (fn [c] (str (:hash c))) new-cards)}])))]
    (is (= "card three" (:source_data (first (:cards state)))))))

(deftest delta-reorder-with-duplicate-hashes-matches-a-full-rebuild
  (let [dir (temp-wiki-dir {"P" "same twin\n\n----\n\nunique card\n\n----\n\nsame twin"})
        store (pagestore/make-page-store (str dir))
        idx (index/build! (index/open-index) store)]
    (try
      (let [cards (vec (index/page-cards idx "P"))
            new-cards [(second cards) (first cards) (last cards)]]
        (spit (io/file dir "P.md") (cards/cards->raw new-cards))
        (index/index-card-delta! idx store "P"
                                 {:op :reorder
                                  :hashes (mapv (fn [c] (str (:hash c))) new-cards)})
        (is (await-search-sync! idx))
        (is (= (rebuilt-state dir "P") (page-state idx "P")))
        (is (= "unique card" (:source_data (first (index/page-cards idx "P"))))))
      (finally
        (index/close! idx)))))

;; endregion

;; region transclusion edges

(deftest transclude-cards-index-their-source-edge
  (let [dir (temp-wiki-dir {"Source" "the source card"
                            "User"   ":transclude\n\n{:from \"Source\"\n :ids [\"abc\"]}"
                            "Other"  "plain page linking [[Source]]"})
        idx (build-index dir)]
    (try
      (testing "transcluded-into finds the transcluding page"
        (is (= ["User"] (vec (index/transcluded-into idx "Source")))))
      (testing "pages nobody transcludes from have no edge"
        (is (= [] (vec (index/transcluded-into idx "User")))))
      (testing "the transclude stanza itself contributes no wiki-link"
        (is (= #{["Other" "Source"]} (indexed-links idx))))
      (finally
        (index/close! idx)))))

(deftest delta-replace-with-transclude-card-updates-the-edge
  (let [new-card (parsing/raw-card-text->card-map
                  ":transclude\n\n{:from \"Elsewhere\"\n :ids [\"zz\"]}")
        state (apply-delta-test
               (fn [cards]
                 [(assoc cards 1 new-card)
                  {:op :replace :target (str (:hash (second cards))) :card new-card}]))]
    (is (= #{["P" "Elsewhere"]} (set (:transclusions state))))))

;; endregion

(deftest broken-transclusions-report
  (let [source "{:card/type :markdown :card/id \"good\"}\n\nthe source card"
        dir (temp-wiki-dir
             {"Source"  source
              "Healthy" ":transclude\n\n{:from \"Source\"\n :ids [\"good\"]}"
              "ByHash"  (str ":transclude\n\n{:from \"Source\"\n :ids [\""
                             (:hash (parsing/raw-card-text->card-map source))
                             "\"]}")
              "BadId"   ":transclude\n\n{:from \"Source\"\n :ids [\"good\" \"gone\"]}"
              "BadIds"  ":transclude\n\n{:from \"Source\"\n :ids \"not-a-vector\"}"
              "BadPage" ":transclude\n\n{:from \"Nowhere\"\n :ids [\"whatever\"]}"})
        idx (build-index dir)]
    (try
      (testing "only unresolvable transclusions are reported, sorted by page"
        (is (= [{:page "BadId" :from "Source" :missing-ids ["gone"]}
                {:page "BadIds" :from "Source" :malformed-ids "not-a-vector"}
                {:page "BadPage" :from "Nowhere" :missing-page true}]
               (index/broken-transclusions idx))))
      (testing "editing the source card breaks hash-addressed transclusions"
        (spit (io/file dir "Source.md")
              "{:card/type :markdown :card/id \"good\"}\n\nthe source card, edited")
        (index/index-page! idx (pagestore/make-page-store (str dir)) "Source")
        (is (= ["ByHash"]
               (->> (index/broken-transclusions idx)
                    (filter (fn [b] (:missing-ids b)))
                    (map :page)
                    (remove #{"BadId"})
                    (vec)))))
      (finally
        (index/close! idx)))))

(deftest deadline-cards-are-flagged-at-index-time
  (let [dir (temp-wiki-dir
             {"Tasks"  "todo card\n\n----\n\nfinish the thing deadline: 2026-09-01"
              "Diary"  "met the deadline: 2026-08-01 for the launch"
              "Plain"  "nothing scheduled here"})
        idx (build-index dir)]
    (try
      (testing "flagged cards are queryable with their text, sorted by page"
        (is (= [["Diary" "met the deadline: 2026-08-01 for the launch"]
                ["Tasks" "finish the thing deadline: 2026-09-01"]]
               (vec (index/deadline-cards idx)))))
      (finally
        (index/close! idx)))))

(deftest delta-replace-updates-the-deadline-flag
  (let [new-card (parsing/raw-card-text->card-map "now scheduled deadline: 2026-12-01")
        state (apply-delta-test
               (fn [cards]
                 [(assoc cards 1 new-card)
                  {:op :replace :target (str (:hash (second cards))) :card new-card}]))]
    (is (= [["P" "now scheduled deadline: 2026-12-01"]] (:deadlines state)))))

;; region write-path hardening (review findings)

(deftest refresh-page!-heals-external-changes
  (let [dir (temp-wiki-dir {"Start" "original body links [[Old]]"})
        store (pagestore/make-page-store (str dir))
        idx (index/build! (index/open-index) store)]
    (try
      (testing "no-op when file and index agree"
        (index/refresh-page! idx store "Start")
        (is (= "original body links [[Old]]" (index/page-body idx "Start"))))
      (testing "an external edit is folded in"
        (let [f (io/file dir "Start.md")]
          (spit f "edited outside links [[New]]")
          (.setLastModified f (+ (.lastModified f) 2000)))
        (index/refresh-page! idx store "Start")
        (is (= "edited outside links [[New]]" (index/page-body idx "Start")))
        (is (= #{["Start" "New"]} (indexed-links idx))))
      (testing "a file created after build is indexed"
        (spit (io/file dir "Later.md") "born on disk")
        (index/refresh-page! idx store "Later")
        (is (index/page-exists? idx "Later")))
      (testing "a deleted file is unindexed"
        (.delete (io/file dir "Later.md"))
        (index/refresh-page! idx store "Later")
        (is (not (index/page-exists? idx "Later"))))
      (finally
        (index/close! idx)))))

(deftest delta-remove-with-duplicate-hashes-heals-to-match-the-file
  ;; move-card! strips EVERY card matching the hash from the file; the
  ;; scoped :remove retracts one, and the post-write check must heal the
  ;; index back to what a rebuild produces
  (let [dir (temp-wiki-dir {"P" "same twin\n\n----\n\nunique card\n\n----\n\nsame twin"})
        store (pagestore/make-page-store (str dir))
        idx (index/build! (index/open-index) store)]
    (try
      (let [page-cards (vec (index/page-cards idx "P"))
            target (str (:hash (first page-cards)))
            survivors (vec (cards/remove-card-by-hash page-cards target))]
        (spit (io/file dir "P.md") (cards/cards->raw survivors))
        (index/index-card-delta! idx store "P" {:op :remove :target target})
        (is (await-search-sync! idx))
        (is (= (rebuilt-state dir "P") (page-state idx "P")))
        (is (= ["unique card"] (mapv :source_data (index/page-cards idx "P")))))
      (finally
        (index/close! idx)))))

(deftest delta-replace-with-unclosed-fence-heals-to-match-the-file
  ;; the replacement passes single-card? (no delimiter inside it), but a
  ;; whole-page re-parse extends the unclosed fence over the following
  ;; delimiter, merging cards -- the post-write check must fall back
  (let [new-card (parsing/raw-card-text->card-map "```\nunclosed fence")
        state (apply-delta-test
               (fn [cards]
                 [(assoc cards 1 new-card)
                  {:op :replace :target (str (:hash (second cards))) :card new-card}]))]
    (is (= 2 (count (:cards state))))))

(deftest non-string-declared-ids-resolve
  (let [dir (temp-wiki-dir {"K" "{:card/type :markdown :card/id :alpha}\n\nkeyword-id card"})
        idx (build-index dir)]
    (try
      (let [[card locator] (index/lookup-card idx "K" :alpha)]
        (is (some? card))
        (is (= :id locator))
        (is (string/ends-with? (:source_data card) "keyword-id card")))
      (testing "a string spelling of the keyword stays distinct"
        (is (nil? (index/lookup-card idx "K" ":alpha"))))
      (finally
        (index/close! idx)))))

(deftest target-resolution-mirrors-card-match-scan-order
  ;; a declared id on an EARLIER card equal to a LATER card's content
  ;; hash resolves to the earlier card, as cards/card-match does
  (let [later-text "the hashed card"
        later-hash (str (:hash (parsing/raw-card-text->card-map later-text)))
        dir (temp-wiki-dir {"P" (str "{:card/type :markdown :card/id \"" later-hash "\"}\n\nfirst card"
                                     "\n\n----\n\n" later-text)})
        idx (build-index dir)]
    (try
      (let [[card locator] (index/lookup-card idx "P" later-hash)]
        (is (= :id locator))
        (is (string/ends-with? (:source_data card) "first card")))
      (finally
        (index/close! idx)))))

;; endregion
