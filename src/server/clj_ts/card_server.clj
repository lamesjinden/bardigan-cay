(ns clj-ts.card-server
  [:require
    [clj-ts.render :as render]
    [clojure.string :as str]
    [clj-ts.query.logic :as ldb]
    [clj-ts.storage.page_store :as pagestore]
    [clj-ts.query.facts-db :as facts]
    [clj-ts.query.card-server-record :as server-record]
    [clj-ts.common :as common]
    [clj-ts.cards.system :as system]
    [clj-ts.cards.cards :as cards]
    [clj-ts.cards.card-data :as card-data]
    [clj-ts.search :as search]
    [clj-ts.export.page-exporter]
    [clj-rss.core :as rss]]
  (:import (clojure.lang Atom)
           (java.util.regex Pattern)))

;; Card Server state is just a defrecord.
;; But two components : the page-store and page-exporter are
;; deftypes in their own right.
;; page-store has all the file-system information that the wiki reads and writes.
;; page-exporter the other info for exporting flat files

(defn create-card-server ^Atom [wiki-name site-url port-no start-page nav-links logic-db page-store page-exporter]
  (atom (server-record/->CardServerRecord
          wiki-name
          site-url
          port-no
          start-page
          nav-links
          logic-db
          page-store
          page-exporter)))

(defn- set-state!
  [^Atom card-server key val]
  (swap! card-server assoc key val))

(defn- set-facts-db!
  [^Atom card-server facts-db]
  {:pre [(satisfies? facts/IFactsDb facts-db)]}
  (set-state! card-server :facts-db facts-db))

(defn regenerate-db!
  [^Atom card-server]
  (let [f (ldb/regenerate-db @card-server)]
    (set-facts-db! card-server f)))

(defn write-page-to-file!
  [^Atom card-server page-name body]
  (pagestore/write-page-to-file! @card-server page-name body)
  (regenerate-db! card-server))

(defn page-exists?
  [server-snapshot page-name]
  (-> (.page-store server-snapshot)
      (.page-exists? page-name)))

(defn- load->cards
  [server-snapshot page-name]
  (as-> server-snapshot $
        (.page-store $)
        (.load-page $ page-name)
        (cards/raw->cards server-snapshot $ {:user-authored? true :for-export? false})))

(defn resolve-text-search [server-snapshot _context arguments _value]
  (let [{:keys [query_string]} arguments
        query-pattern-str (str "(?i)" (Pattern/quote query_string))
        out (search/search server-snapshot query-pattern-str query_string)]
    {:result_text out}))

(defn resolve-source-page
  [server-snapshot _context arguments _value]
  (let [{:keys [page_name]} arguments
        ps (.page-store server-snapshot)]
    (if (.page-exists? ps page_name)
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
       :cards           (cards/raw->cards server-snapshot (render/missing-page page_name) {:user-authored? false :for-export? false})
       :system_cards    (let [sim-names (map #(str "\n- [[" % "]]") (.similar-page-names ps page_name))]
                          (if (empty? sim-names)
                            []
                            [(card-data/package-card
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
                      :description "Recent Changes in CardiganBay Wiki"}
                     rc)))

; endregion

(defn- append-card-to-page!
  [^Atom card-server page-name {:keys [source_type source_type_implicit? source_data] :as _card}]
  (let [server-snapshot @card-server
        page-body (try
                    (pagestore/read-page server-snapshot page-name)
                    (catch Exception _ (str "Automatically created a new page : " page-name "\n\n")))
        new-body (if (and (= source_type :markdown) source_type_implicit?)
                   (str page-body "\n\n" "----" "\n\n" (str/trim source_data) "\n\n")
                   (str page-body "\n\n" "----" "\n" source_type "\n\n" (str/trim source_data) "\n\n"))]
    (write-page-to-file! card-server page-name new-body)))

(defn move-card!
  [^Atom card-server page-name hash destination-name]
  (if (= page-name destination-name)
    nil                                                     ;; don't try to move to self
    (let [server-snapshot @card-server
          ps (.page-store server-snapshot)
          from-cards (.get-page-as-card-maps ps page-name)
          card (common/find-card-by-hash from-cards hash)
          stripped (into [] (common/remove-card-by-hash from-cards hash))
          stripped-raw (common/cards->raw stripped)]
      (when (not (nil? card))
        (append-card-to-page! card-server destination-name card)
        (write-page-to-file! card-server page-name stripped-raw)))))

(defn reorder-card!
  [^Atom card-server page-name hash direction]
  (let [server-snapshot @card-server
        page-store (.page-store server-snapshot)
        cards (.get-page-as-card-maps page-store page-name)
        new-cards (if (= "up" direction)
                    (common/move-card-up cards hash)
                    (common/move-card-down cards hash))]
    (write-page-to-file! card-server page-name (common/cards->raw new-cards))))

(defn replace-card!
  [^Atom card-server page-name hash new-body]
  (let [server-snapshot @card-server
        page-store (.page-store server-snapshot)
        cards (.get-page-as-card-maps page-store page-name)
        match (common/find-card-by-hash cards hash)]
    (if (not match)
      :not-found
      (let [source-type (:source_type match)
            source-type-implicit? (:source_type_implicit? match)
            new-body (if (and (= source-type :markdown) source-type-implicit?)
                       new-body
                       (str source-type "\n" new-body))
            new-card (card-data/raw-card-text->card-map new-body)
            new-cards (common/replace-card
                        cards
                        #(common/match-hash % hash)
                        new-card)]
        (write-page-to-file! card-server page-name (common/cards->raw new-cards))
        (let [render-context {:user-authored? true :for-export? false}
              packaged-card (-> (cards/process-card-map server-snapshot -1 new-card render-context)
                                (first)
                                (dissoc :id))]
          packaged-card)))))

(defn load-media-file [server-snapshot file-name]
  (-> server-snapshot :page-store (.load-media-file file-name)))
