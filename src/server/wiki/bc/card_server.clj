(ns wiki.bc.card-server
  (:require [clojure.string :as str]
            [clj-rss.core :as rss]
            [wiki.bc.cards.cards :as cards]
            [wiki.bc.cards.packaging :as packaging]
            [wiki.bc.cards.parsing :as parsing]
            [wiki.bc.cards.system :as system]
            [wiki.bc.query.card-server-record :as server-record]
            [wiki.bc.query.index-db :as index-db]
            [wiki.bc.render :as render]
            [wiki.bc.search :as search]
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

(defn create-card-server ^Atom [wiki-name site-url port-no start-page nav-links page-index page-store]
  (atom (server-record/->CardServerRecord
         wiki-name
         site-url
         port-no
         start-page
         nav-links
         (index-db/make-facts-db page-index)
         (indexed-page-store/make-indexed-page-store page-index page-store)
         page-index)))

(defn write-page-to-file!
  "The single mutation path for page content: the indexed page-store
  writes the file and re-indexes, then RecentChanges is updated."
  [^Atom card-server page-name body]
  (pagestore/write-page-to-file! @card-server page-name body))

(defn page-exists?
  [server-snapshot page-name]
  (-> (.page-store server-snapshot)
      (.page-exists? page-name)))

(defn- load->cards
  [server-snapshot page-name]
  (as-> server-snapshot $
    (.page-store $)
    (.load-page $ page-name)
    (packaging/raw->cards server-snapshot $ {:user-authored? true :for-export? false})))

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

(defn resolve-page
  [server-snapshot _context arguments _value]
  (let [{:keys [page_name]} arguments
        ps (:page-store server-snapshot)
        wiki-name (:wiki-name server-snapshot)
        site-url (:site-url server-snapshot)
        start-page-name (:start-page server-snapshot)
        nav-links (:nav-links server-snapshot)]
    (if (.page-exists? ps page_name)
      {:page_name       page_name
       :wiki_name       wiki-name
       :site_url        site-url
       :public_root     (str site-url "/view/")
       :start_page_name start-page-name
       :nav-links       nav-links
       :cards           (load->cards server-snapshot page_name)
       :system_cards    [(system/backlinks server-snapshot page_name)]}
      {:page_name       page_name
       :wiki_name       wiki-name
       :site_url        site-url
       :start_page_name start-page-name
       :public_root     (str site-url "/view/")
       :nav-links       nav-links
       :cards           (packaging/raw->cards server-snapshot (render/missing-page page_name) {:user-authored? false :for-export? false})
       :system_cards    (let [sim-names (map #(str "\n- [[" % "]]") (.similar-page-names ps page_name))]
                          (if (empty? sim-names)
                            []
                            [(util/package-card
                              :similarly_name_pages :system :markdown ""
                              (str "Here are some similarly named pages :"
                                   (apply str sim-names)) false)]))})))

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

(defn- append-card-to-page!
  [^Atom card-server page-name {:keys [source_data] :as _card}]
  (let [server-snapshot @card-server
        page-body (try
                    (pagestore/read-page server-snapshot page-name)
                    (catch Exception _ (str "Automatically created a new page : " page-name "\n\n")))
        new-body (str page-body "\n\n" "----" "\n\n" (str/trim source_data) "\n\n")]
    (write-page-to-file! card-server page-name new-body)))

(defn move-card!
  [^Atom card-server page-name hash destination-name]
  (if (= page-name destination-name)
    ;; don't try to move to self
    nil
    (let [server-snapshot @card-server
          page-store (.page-store server-snapshot)
          from-cards (.get-page-as-card-maps page-store page-name)
          card (cards/find-card-by-hash from-cards hash)
          stripped (into [] (cards/remove-card-by-hash from-cards hash))
          stripped-raw (cards/cards->raw stripped)]
      (when (not (nil? card))
        (append-card-to-page! card-server destination-name card)
        (write-page-to-file! card-server page-name stripped-raw)))))

(defn reorder-card!
  [^Atom card-server page-name hash direction]
  (let [server-snapshot @card-server
        page-store (.page-store server-snapshot)
        cards (.get-page-as-card-maps page-store page-name)
        new-cards (condp = direction
                    "up" (cards/move-card-up cards hash)
                    "down" (cards/move-card-down cards hash)
                    "start" (cards/move-card-to-start cards hash)
                    "end" (cards/move-card-to-end cards hash)
                    :else cards)]
    (write-page-to-file! card-server page-name (cards/cards->raw new-cards))))

(defn replace-card!
  [^Atom card-server page-name hash new-body]
  (let [server-snapshot @card-server
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
        (write-page-to-file! card-server page-name (cards/cards->raw new-cards))
        (let [render-context {:user-authored? true :for-export? false}
              packaged-card (-> (packaging/process-card-map server-snapshot -1 new-card render-context)
                                (first)
                                (dissoc :id))]
          packaged-card)))))

(defn load-media-file [server-snapshot file-name]
  (-> server-snapshot :page-store (.load-media-file file-name)))

(defn- append-to-page!
  [^Atom card-server page-name source-data]
  (let [server-snapshot @card-server
        page-store (.page-store server-snapshot)
        page-body (.load-page page-store page-name)
        new-body (str page-body "\n\n" "----" "\n\n" (str/trim source-data) "\n\n")]
    (write-page-to-file! card-server page-name new-body)))

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
  (let [server-snapshot @card-server
        page-store (.page-store server-snapshot)
        source-data (str/trim source-data)]
    (if (.page-exists? page-store destination-name)
      (append-to-page! card-server destination-name source-data)
      (append-to-new-page! card-server destination-name source-data))))
