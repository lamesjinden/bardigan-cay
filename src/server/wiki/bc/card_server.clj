(ns wiki.bc.card-server
  (:require [clojure.string :as str]
            [clj-rss.core :as rss]
            [wiki.bc.cards.cards :as cards]
            [wiki.bc.cards.packaging :as packaging]
            [wiki.bc.cards.parsing :as parsing]
            [wiki.bc.cards.system :as system]
            [wiki.bc.query.card-server-record :as server-record]
            [wiki.bc.query.git-facts-db :as git-facts-db]
            [wiki.bc.query.index-db :as index-db]
            [wiki.bc.render :as render]
            [wiki.bc.search :as search]
            [wiki.bc.storage.git-page-store :as git-page-store]
            [wiki.bc.storage.git-repo :as git-repo]
            [wiki.bc.storage.indexed-page-store :as indexed-page-store]
            [wiki.bc.storage.page-store :as pagestore]
            [wiki.bc.util :as util])
  (:import (clojure.lang Atom)))

;; Card Server state is just a defrecord.
;; Its storage components are deftypes in their own right, all built over
;; the page-index (a disposable Datalevin index of the page files): the
;; facts-db is a live query view over the index, and the page-store reads
;; from the index while writing through to the files (see
;; wiki.bc.storage.indexed-page-store).

;; git-repo (see wiki.bc.storage.git-repo) is nil when the wiki directory
;; is not inside a git repository; the revision views are then unavailable
(defn create-card-server
  (^Atom [wiki-name site-url port-no start-page nav-links page-index page-store]
   (create-card-server wiki-name site-url port-no start-page nav-links page-index page-store nil))
  (^Atom [wiki-name site-url port-no start-page nav-links page-index page-store git-repo]
   (atom (server-record/->CardServerRecord
          wiki-name
          site-url
          port-no
          start-page
          nav-links
          (index-db/make-facts-db page-index)
          (indexed-page-store/make-indexed-page-store page-index page-store)
          page-index
          git-repo))))

;; Serializes every mutation: the file write + index update pair, and the
;; read-modify-write card operations around them. Without it, concurrent
;; saves from separate http-kit worker threads can interleave file and
;; index writes and leave the two permanently diverged. Monitors are
;; re-entrant, so the write fns nest freely inside locked card operations.
(def ^:private write-lock (Object.))

(defn- refresh-page-from-disk!
  "Folds any external change to page-name's file (hand edit, git pull,
  create, delete) into the store's derived state. Every mutating
  operation that reads page content it will write back calls this first;
  skipping it would overwrite the external edit with the stale copy."
  [server-snapshot page-name]
  (-> (.page-store server-snapshot)
      (.refresh-page! page-name)))

(defn write-page-to-file!
  "The single mutation path for page content: the indexed page-store
  writes the file and re-indexes, then RecentChanges is updated."
  [^Atom card-server page-name body]
  (locking write-lock
    (pagestore/write-page-to-file! @card-server page-name body)))

(defn- write-page-delta-to-file!
  "write-page-to-file! for card-level edits: the delta lets the store
  reindex just the affected card instead of re-parsing the page."
  [^Atom card-server page-name body delta]
  (locking write-lock
    (pagestore/write-page-delta-to-file! @card-server page-name body delta)))

(defn page-exists?
  [server-snapshot page-name]
  (-> (.page-store server-snapshot)
      (.page-exists? page-name)))

(defn- load->cards
  [server-snapshot page-name]
  (as-> server-snapshot $
    (.page-store $)
    (.get-page-as-card-maps $ page-name)
    (packaging/card-maps->cards server-snapshot $ {:user-authored? true :for-export? false})))

(defn resolve-text-search [server-snapshot _context arguments _value]
  (let [{:keys [query_string]} arguments
        query-pattern-str (util/string->pattern-string query_string)
        {:keys [query name-matches text-matches]} (search/search server-snapshot query-pattern-str query_string)]
    {:query query
     :name_matches name-matches
     :text_matches text-matches}))

(defn resolve-autocomplete-search [server-snapshot _context arguments _value]
  (let [{:keys [query_string]} arguments
        query-pattern-str (util/string->pattern-string query_string)
        db (-> server-snapshot :facts-db)
        all-pages (.all-pages db)
        name-matches (pagestore/name-search all-pages (re-pattern query-pattern-str))]
    (->> name-matches
         (take 10)
         (map (fn [page-name]
                {:name page-name
                 :path page-name})))))

(defn resolve-source-page
  [server-snapshot _context arguments _value]
  (let [{:keys [page_name]} arguments
        page-store (.page-store server-snapshot)]
    (if (.page-exists? page-store page_name)
      {:page_name page_name
       :body      (pagestore/read-page server-snapshot page_name)}
      {:page_name page_name
       :body
       (str "A PAGE CALLED " page_name " DOES NOT EXIST
Check if the name you typed, or in the link you followed is correct.
If you would *like* to create a page with this name, simply click the [Edit] button to edit this text. When you save, you will create the page")})))

(defn git-enabled?
  "Whether the wiki directory sits inside a git repository -- the
  precondition for every revision view."
  [server-snapshot]
  (some? (:git-repo server-snapshot)))

(defn- page-config
  "The wiki-wide part of every prepared page."
  [server-snapshot]
  (let [site-url (:site-url server-snapshot)]
    {:wiki_name       (:wiki-name server-snapshot)
     :site_url        site-url
     :public_root     (str site-url "/view/")
     :start_page_name (:start-page server-snapshot)
     :nav-links       (:nav-links server-snapshot)
     :git_enabled     (git-enabled? server-snapshot)}))

(defn- page-revisions
  "The commits that touched page-name, newest first; empty outside a
  repository. Sent with every prepared page so the client has the list
  in hand before the user asks for it."
  [server-snapshot page-name]
  (if (git-enabled? server-snapshot)
    (git-repo/page-revisions (:git-repo server-snapshot) page-name)
    []))

(defn- similarly-named-pages-cards [ps page_name]
  (let [sim-names (map #(str "\n- [[" % "]]") (.similar-page-names ps page_name))]
    (if (empty? sim-names)
      []
      [(util/package-card
        :similarly_name_pages :system :markdown ""
        (str "Here are some similarly named pages :"
             (apply str sim-names)) false)])))

(def ^:private not-user-authored {:user-authored? false :for-export? false})

(defn resolve-page
  [server-snapshot _context arguments _value]
  (let [{:keys [page_name]} arguments
        ps (:page-store server-snapshot)]
    (merge (page-config server-snapshot)
           {:page_name page_name
            :revisions (page-revisions server-snapshot page_name)}
           (if (.page-exists? ps page_name)
             {:cards        (load->cards server-snapshot page_name)
              :system_cards [(system/backlinks server-snapshot page_name)]}
             {:cards        (packaging/raw->cards server-snapshot (render/missing-page page_name) not-user-authored)
              :system_cards (similarly-named-pages-cards ps page_name)}))))

;; Revisions: the wiki as committed at one git revision, read-only.

(defn resolve-commit
  "The full sha for rev (a sha, abbreviated sha or ref name), or nil
  when rev names no commit."
  [server-snapshot rev]
  (git-repo/resolve-commit (:git-repo server-snapshot) rev))

(defn- revision-snapshot
  "server-snapshot with its storage swapped for read-only views of the
  wiki at commit sha: pages -- and so transclusions -- read from the
  commit, the facts-db answers only the page list, and there is no
  page-index. Media still comes from the live store."
  [server-snapshot sha]
  (let [git-store (git-page-store/make-git-page-store (:git-repo server-snapshot) sha (:page-store server-snapshot))]
    (assoc server-snapshot
           :page-store git-store
           :facts-db (git-facts-db/make-git-facts-db git-store)
           :page-index nil)))

(defn- missing-page-at-revision [page-name revision]
  (str "A PAGE CALLED " page-name " DID NOT EXIST AT REVISION " (:short_sha revision) "

This is a read-only view of the wiki as committed on " (:date revision) "."))

(defn resolve-revision-source-page
  "resolve-source-page for page-name at commit sha; an empty body for a
  page absent at that commit."
  [server-snapshot page-name sha]
  {:page_name page-name
   :body      (or (git-repo/page-body (:git-repo server-snapshot) sha page-name) "")})

(defn resolve-revision-page
  "resolve-page for page-name as committed at sha (a full sha, see
  resolve-commit). The result carries the commit under :revision and no
  system cards: backlinks describe the present, not the commit."
  [server-snapshot page-name sha]
  (let [snapshot (revision-snapshot server-snapshot sha)
        ps (:page-store snapshot)
        revision (git-repo/commit-info (:git-repo server-snapshot) sha)]
    (merge (page-config server-snapshot)
           {:page_name    page-name
            :revision     revision
            :revisions    (page-revisions server-snapshot page-name)
            :cards        (if (.page-exists? ps page-name)
                            (load->cards snapshot page-name)
                            (packaging/raw->cards snapshot (missing-page-at-revision page-name revision) not-user-authored))
            :system_cards []})))

; region RecentChanges as RSS

(defn rss-recent-changes
  [server-snapshot link-fn]
  (let [ps (:page-store server-snapshot)
        make-link (fn [s]
                    (let [m (re-matches #"\* \[\[(\S+)\]\] (\(.+\))" s)
                          [pname date] [(second m) (nth m 2)]]
                      {:title (str pname " changed on " date)
                       :link  (link-fn pname)}))
        rc (-> (.read-recent-changes ps)
               str/split-lines
               (#(map make-link %)))]
    (rss/channel-xml {:title       "RecentChanges"
                      :link        (-> server-snapshot :site-url)
                      :description "Recent Changes in BardiganCay Wiki"}
                     rc)))

; endregion

(defn- append-to-page!
  [^Atom card-server page-name source-data]
  (let [server-snapshot @card-server
        page-store (.page-store server-snapshot)
        page-body (.load-page page-store page-name)
        appended (str/trim source-data)
        new-body (str page-body "\n\n" "----" "\n\n" appended "\n\n")]
    (write-page-delta-to-file! card-server page-name new-body
                               {:op :append
                                :card (parsing/raw-card-text->card-map appended)})))

(defn- append-to-new-page!
  [^Atom card-server page-name source-data]
  (let [server-snapshot @card-server
        page-body (try
                    (pagestore/read-page server-snapshot page-name)
                    (catch Exception _ (str "Automatically created a new page : " page-name "\n\n")))
        new-body (str page-body "\n\n" "----" "\n\n" (str/trim source-data) "\n\n")]
    (write-page-to-file! card-server page-name new-body)))

(defn append-page!
  [^Atom card-server destination-name source-data]
  (locking write-lock
    (let [server-snapshot @card-server
          _ (refresh-page-from-disk! server-snapshot destination-name)
          page-store (.page-store server-snapshot)
          source-data (str/trim source-data)]
      (if (.page-exists? page-store destination-name)
        (append-to-page! card-server destination-name source-data)
        (append-to-new-page! card-server destination-name source-data)))))

(defn move-card!
  [^Atom card-server page-name hash destination-name]
  (if (= page-name destination-name)
    ;; don't try to move to self
    nil
    (locking write-lock
      (let [server-snapshot @card-server
            _ (refresh-page-from-disk! server-snapshot page-name)
            page-store (.page-store server-snapshot)
            from-cards (.get-page-as-card-maps page-store page-name)
            card (cards/find-card-by-hash from-cards hash)
            stripped (into [] (cards/remove-card-by-hash from-cards hash))
            stripped-raw (cards/cards->raw stripped)]
        (when (not (nil? card))
          (append-page! card-server destination-name (:source_data card))
          (write-page-delta-to-file! card-server page-name stripped-raw
                                     {:op :remove :target hash}))))))

(defn reorder-card!
  [^Atom card-server page-name hash direction]
  (locking write-lock
    (let [server-snapshot @card-server
          _ (refresh-page-from-disk! server-snapshot page-name)
          page-store (.page-store server-snapshot)
          cards (.get-page-as-card-maps page-store page-name)
          ;; the bare trailing expression is condp's default: an
          ;; unrecognized direction is a no-op, not a "no matching clause"
          new-cards (condp = direction
                      "up" (cards/move-card-up cards hash)
                      "down" (cards/move-card-down cards hash)
                      "start" (cards/move-card-to-start cards hash)
                      "end" (cards/move-card-to-end cards hash)
                      cards)]
      (write-page-delta-to-file! card-server page-name (cards/cards->raw new-cards)
                                 {:op :reorder
                                  :hashes (mapv (fn [c] (str (:hash c))) new-cards)}))))

(defn replace-card!
  [^Atom card-server page-name hash new-body]
  (locking write-lock
    (let [server-snapshot @card-server
          _ (refresh-page-from-disk! server-snapshot page-name)
          page-store (.page-store server-snapshot)
          cards (.get-page-as-card-maps page-store page-name)
          match (cards/find-card-by-hash cards hash)]
      (if (not match)
        :not-found
        (let [new-card (parsing/raw-card-text->card-map new-body)
              new-cards (cards/replace-card
                         cards
                         #(cards/card-matches % hash)
                         new-card)]
          (write-page-delta-to-file! card-server page-name (cards/cards->raw new-cards)
                                     {:op :replace :target hash :card new-card})
          (let [render-context {:user-authored? true :for-export? false}
                packaged-card (-> (packaging/process-card-map server-snapshot -1 new-card render-context)
                                  (first)
                                  (dissoc :id))]
            packaged-card))))))

(defn load-media-file [server-snapshot file-name]
  (-> server-snapshot :page-store (.load-media-file file-name)))
