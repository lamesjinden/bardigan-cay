(ns clj-ts.card-server
  [:require
   [clojure.string :as string]
   [clojure.pprint :refer [pprint]]

   [clj-ts.logic :as ldb]
   [clj-ts.pagestore :as pagestore]
   [clj-ts.common :as common]
   [clj-ts.types :as types]
   [clj-ts.embed :as embed]
   [clj-ts.patterning :as patterning]

   [com.walmartlabs.lacinia.util :refer [attach-resolvers]]
   [com.walmartlabs.lacinia.schema :as schema]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clj-rss.core :as rss]

   [sci.core :as sci]

   [hiccup.core :refer [html]]


   ]
  (:import (java.net InetAddress)
           (java.net DatagramSocket))

  )




;; Card Server State is ALL the global state for the application.
;; NOTHING mutable should be stored anywhere else but in the card-server-state atom.

;; Card Server state is just a defrecord.
;; But two components : the page-store and page-exporter are
;; deftypes in their own right.
;; page-store has all the file-system information that the wiki reads and writes.
;; page-exporter the other info for exporting flat files

(defprotocol ICardServerRecord)



(defmacro dnn [cs m & args]
  `(let [db# (:facts-db ~cs)]
     (if (nil? db#) :not-available
         (. db# ~m ~@args))
     ))



(defrecord CardServerRecord
    [wiki-name site-url port-no start-page facts-db page-store page-exporter]

  ldb/IFactsDb
  (raw-db [cs] (dnn cs raw-db))
  (all-pages [cs] (dnn cs all-pages) )
  (all-links [cs] (dnn cs all-links) )
  (broken-links [cs] (dnn cs broken-links))
  (orphan-pages [cs] (dnn cs orphan-pages))
  (links-to [cs p-name] (dnn cs links-to p-name)))





;; State Management is done at the card-server level

(def the-server-state  (atom :dummy))

(defn initialize-state! [wiki-name site-url port-no start-page logic-db page-store page-exporter]
  (reset! the-server-state
          (->CardServerRecord
           wiki-name
           site-url
           port-no
           start-page
           logic-db
           page-store
           page-exporter) )
  )

(defn server-state
  "Other modules should always get the card-server data through calling this function.
  Rather than relying on knowing the name of the atom"
  [] @the-server-state)

(defn set-state!
  "The official API call to update any of the key-value pairs in the card-server state"
  [key val]
  (swap! the-server-state assoc key val))

;; convenience functions for updating state
(defn set-wiki-name! [wname]
  (set-state! :wiki-name wname))

(defn set-site-url! [url]
  (set-state! :site-url url))

(defn set-start-page! [pagename]
  (set-state! :start-page pagename))

(defn set-port! [port]
  (set-state! :port-no port))

(defn set-facts-db! [facts]
  {:pre [(satisfies? ldb/IFactsDb facts)]}
  (set-state! :facts-db facts))

(defn set-page-store! [page-store]
  {:pre [(satisfies? types/IPageStore page-store)]}
  (set-state! :page-store page-store))

(defn set-page-exporter! [page-exporter]
  {:pre [(= (type page-exporter) types/IPageExporter)]}
  (set-state! :page-exporer page-exporter))



;; PageStore delegation

(declare regenerate-db!)

(defn write-page-to-file! [p-name body]
  (do
    (pagestore/write-page-to-file! (server-state) p-name body)
    (regenerate-db!)
    ))


(defn update-pagedir! [new-pd new-ed]
  (let [new-ps
        (pagestore/make-page-store
         new-pd
         new-ed)]
    (set-page-store! new-ps)
    (regenerate-db!)))

(defn page-exists? [page-name]
  (-> (.page-store (server-state))
      (.page-exists? page-name)))

(defn read-page [page-name]
  (-> (.page-store (server-state))
      (.read-page page-name)))



;; Logic delegation

(defn regenerate-db! []
  (future
    (println "Starting to rebuild logic db")
    (let [f (ldb/regenerate-db (server-state)) ]
      (set-facts-db! f )
      (println "Finished building logic db"))) )



;; Useful for errors

(defn exception-stack [e]
  (let [sw (new java.io.StringWriter)
        pw (new java.io.PrintWriter sw)]
    (.printStackTrace e pw)
    (str "Exception :: " (.getMessage e) (-> sw .toString) ))  )

;; Other functions

(defn search [term]
  (let [db (-> (server-state) :facts-db)
        all-pages (.all-pages db)

        name-res (pagestore/name-search (server-state) all-pages
                                        (re-pattern term))

        count-names (count name-res)

        res (pagestore/text-search (server-state) all-pages
                                   (re-pattern term))

        count-res (count res)

        name-list (apply str (map #(str "* [[" % "]]\n") name-res))
        res-list (apply str (map #(str "* [[" % "]]\n") res))


        out
        (str "

*" count-names " PageNames containing \"" term "\"*\n
" name-list "

*" count-res " Pages containing \" " term "\"*\n "
             res-list
            )]

    out
    )
  )

;; Card Processing

(defn server-eval
  "Evaluate Clojure code embedded in a card. Evaluated on the server. Be careful."
  [data]
  (let [code data
        evaluated
        (try
          (#(apply str (sci/eval-string code)))
          (catch Exception e exception-stack))]
    evaluated
    ))


(defn server-custom-script
  "Evaluate a script from system/custom/ with arguments"
  [data]
  (do
    (println "In server-custom-script")
    (str "This will (eventually) run a custom script: " data))
  )


(defn ldb-query->mdlist-card [i source_data title result qname f user-authored?]
  (let [items (apply str (map f result))
        body (str "*" title "* " "*(" (count result) " items)*\n\n" items )  ]
    (common/package-card i :system :markdown source_data body user-authored?)))

(defn item1 [s] (str "* [[" s "]]\n"))


(defn system-card [i data user-authored?]
  (let [
        info (read-string data)
        cmd (:command info)
        db (-> (server-state) :facts-db)
        ps (-> (server-state) :page-store)]

    (condp = cmd
      :allpages
      (ldb-query->mdlist-card i data "All Pages" (.all-pages db) :allpages item1 user-authored?)

      :alllinks
      (ldb-query->mdlist-card
       i data "All Links" (.all-links db) :alllinks
       (fn [[a b]] (str "[[" a "]],, &#8594;,, [[" b "]]\n"))
       user-authored?)

      :brokenlinks
      (ldb-query->mdlist-card
       i data "Broken Internal Links" (.broken-links db) :brokenlinks
       (fn [[a b]] (str "[[" a "]],, &#8603;,, [[" b "]]\n"))
       user-authored?)

      :orphanpages
      (ldb-query->mdlist-card
       i data "Orphan Pages" (.orphan-pages db) :orphanpages item1
       user-authored?)

      :recentchanges
      (let [src (.read-recentchanges ps) ]
        (common/package-card
         "recentchanges" :system :markdown src src user-authored?))

      :search
      (let [res (search (:query info) ) ]
        (common/package-card
         "search" :system :markdown
         data res user-authored?))

      :about
      (let [sr (str "### System Information

**Wiki Name**,, " (:wiki-name (server-state)   )  "
**PageStore Directory** (relative to code) ,, " (.page-path ps) "
**Is Git Repo?**  ,, " (.git-repo? ps) "
**Site Url Root** ,, " (:site-url (server-state)) "
**Export Dir** ,, " (.export-path ps) "
**Number of Pages** ,, " (count (.all-pages db))
                    )]
        (common/package-card i :system :markdown data sr user-authored?))

      :customscript
      (let [return-type (or (:return-type data) :markdown)
            sr (server-custom-script data) ]
        (common/package-card i :customscript return-type data sr user-authored?))



      ;; not recognised
      (let [d (str "Not recognised system command in " data  " -- cmd " cmd )]
        (common/package-card i :system :raw data d user-authored?)))
    ))



(declare card-maps->processed)

(defn transclude [i data for-export? user-authored?]
  (let [{:keys [from process ids]} (read-string data)
        ps (.page-store (server-state))
        matched-cards (.get-cards-from-page ps from ids)
        cards (card-maps->processed (* 100 i) matched-cards for-export? user-authored?)

        body (str "## Transcluded from [[" from "]]")
        ]
    (concat [(common/package-card i :transclude :markdown body body user-authored?)] cards )))

(defn bookmark-card [data]
  (let [{:keys [url timestamp]} (read-string data)]
    (str "
Bookmarked " timestamp  ": <" url ">

")))



(defn afind [n ns]
  (cond (empty? ns) nil
        (= n (-> ns first first))
        (-> ns first rest)
        :otherwise (afind n (rest ns))))



(defn network-card [i data for-export? user-authored?]
  (try
    (let [
          nodes (-> data read-string :nodes)
          arcs (-> data read-string :arcs)

          maxit (fn [f i xs]
                  (apply f (map  #(nth % i) xs ) ))
          maxx (maxit max 2 nodes)
          maxy (maxit max 3 nodes)
          minx (maxit min 2 nodes)
          miny (maxit min 3 nodes)

          node (fn [[n label x y]]
                 (let [an-id (gensym)
                       the-text [:text {:class "wikilink"
                                        :data label
                                        :x x :y (+ y 20)
                                        :text-anchor "middle"
                                        :fill "black"
                                        } label]

                       final-text
                       (if for-export?
                         [:a {:href label} the-text ]
                         the-text
                         )
                       box [:circle {:cx x :cy y :r 20
                                     :width 100 :height 20
                                     :stroke "orange"
                                     :stroke-width 2 :fill "yellow"}
                            ]

                       ]
                   (html [:g {:id an-id} box final-text])))
          arc (fn [[n1 n2]]
                (let
                    [a1 (afind n1 nodes)
                     a2 (afind n2 nodes)]
                  (if
                      (and a1 a2)
                    (let [[label x1 y1] a1
                          [label x2 y2] a2]
                      (html [:line {:x1 x1  :y1 y1 :x2 x2 :y2 y2
                                    :stroke "#000" :stroke-width 2
                                    :marker-end "url(#arrowhead)"}])

                      )
                    "")))
          svg (html [:svg {:width "500px" :height "400px"
                           :viewBox (str "0 0 " (* 1.3 maxx) (* 1.3 maxy)) }
                     [:defs [:marker {:id "arrowhead" :markerWidth "10" :markerHeight "7"
                                      :refX "-5" :refY "3.5" :orient "auto"}
                             [:polygon {:points "-5 0, 0 3.5, -5 7"}]]]
                     (apply str (map arc arcs))
                     (apply str (map node nodes))
                     ])
          ]

      (common/package-card i :network :markdown data svg user-authored?)
      )
    (catch Exception e (common/package-card i :network :raw data
                                            (str (exception-stack e)
                                                 "\n" data)
                                            user-authored?))
    )
  )

(defn process-card-map
  [i {:keys [source_type source_data]} for-export?  user-authored?]
  (try
    (if (= source_type :transclude)
      (transclude i source_data for-export? user-authored?)
      [(condp = source_type
         :markdown (common/package-card i source_type :markdown source_data source_data user-authored?)
         :manual-copy (common/package-card i source_type :manual-copy source_data source_data user-authored?)

         :raw (common/package-card i source_type :raw source_data source_data user-authored?)

         :code
         (do
           (println "Exporting :code card " )
           (common/package-card i :code :code source_data source_data user-authored?))

         :evalraw
         (common/package-card i :evalraw :raw source_data (server-eval source_data) user-authored?)

         :evalmd
         (common/package-card i :evalmd :markdown source_data (server-eval source_data) user-authored?)

         :workspace
         (common/package-card i source_type :workspace source_data source_data user-authored?)

         :system
         (system-card i source_data user-authored?)

         :embed
         (common/package-card i source_type :html source_data
                              (embed/process source_data for-export?
                                             (fn [s] (common/md->html s))
                                             (server-state))
                              user-authored?)

         :bookmark
         (common/package-card i :bookmark :markdown source_data (bookmark-card source_data) user-authored?)


         :network
         (network-card i source_data for-export? user-authored?)

         :patterning
         (common/package-card i :patterning :html source_data
                              (patterning/one-pattern source_data) user-authored?)

         ;; not recognised
         (common/package-card i source_type source_type source_data source_data user-authored?)
         )])
    (catch
        Exception e
      [(common/package-card
        i :raw :raw source_data
        (str "Error \n\nType was\n" source_type
             "\nSource was\n" source_data
             "\n\nStack trace\n"
             (exception-stack e))
        user-authored?)])
    )
  )

(defn card-maps->processed [id-start card-maps for-export? user-authored?]
  (mapcat process-card-map (iterate inc id-start) card-maps (repeat for-export?) (repeat user-authored?))  )

(defn raw->cards [raw for-export? user-authored?]
  (let [card-maps (common/raw-text->card-maps raw)]
    (card-maps->processed 0 card-maps for-export? user-authored?)))

(declare backlinks)

(defn load->cards [page-name]
  (-> (server-state) .page-store
      (.read-page page-name)
      (raw->cards false true))
  )

(defn load->cards-for-export [page-name]
  (-> (server-state) .page-store
      (.read-page page-name)
      (raw->cards true true)))

(defn generate-system-cards [page-name]
 [(backlinks page-name)] )

(defn load-one-card [page-name hash]
  (let [cards (load->cards page-name)]
    (common/find-card-by-hash cards hash)))

;; GraphQL resolvers

(defn resolve-text-search [context arguments value]
  (let [{:keys [query_string]} arguments
        out (search query_string)]
    {:result_text out}
    ))

(defn resolve-card
    "Not yet tested"
  [context arguments value user-authored?]
  (let [{:keys [page_name hash]} arguments
        ps (.page-store (server-state))]
    (if (.page-exists? ps page_name)
      (-> (load->cards page_name)
          (common/find-card-by-hash hash))
      (common/package-card 0 :markdown :markdown
                           (str "Card " hash " doesn't exist in " page_name)
                           (str "Card " hash " doesn't exist in " page_name)
                           user-authored?) )))

(defn resolve-source-page [context arguments value]
  (let [{:keys [page_name]} arguments
        ps (.page-store (server-state))]
    (if (.page-exists? ps page_name)
      {:page_name page_name
       :body (pagestore/read-page (server-state) page_name)}
      {:page_name page_name
       :body
       (str "A PAGE CALLED " page_name " DOES NOT EXIST


Check if the name you typed, or in the link you followed is correct.

If you would *like* to create a page with this name, simply click the [Edit] button to edit this text. When you save, you will create the page")
       })))


(defn resolve-page [context arguments value]
  (let [{:keys [page_name]} arguments
        ps (:page-store (server-state))
        wiki-name (:wiki-name (server-state))
        site-url (:site-url (server-state))
        port (:port-no (server-state))
        start-page-name (:start-page (server-state))
        ip (try
             (let [dgs (new DatagramSocket)]
               (.connect dgs (InetAddress/getByName "8.8.8.8") 10002)
               (-> dgs .getLocalAddress .getHostAddress))

             (catch Exception e (str e))
            )

        ]

    (if (.page-exists? ps page_name)
      {:page_name page_name
       :wiki_name wiki-name
       :site_url site-url
       :port port
       :ip ip
       :public_root (str site-url "/view/")
       :start_page_name start-page-name
       :cards (load->cards page_name)
       :system_cards (generate-system-cards page_name)
       }
      {:page_name page_name
       :wiki_name wiki-name
       :site_url site-url
       :port port
       :ip ip
       :start_page_name start-page-name
       :public_root (str site-url "/view/")
       :cards (raw->cards
               (str "<div style='color:#990000'>A PAGE CALLED " page_name " DOES NOT EXIST


Check if the name you typed, or in the link you followed is correct.

If you would *like* to create a page with this name, simply click the [Edit] button to edit this text. When you save, you will create the page
</div>")
               false false)
       :system_cards
       (let [sim-names (map
                        #(str "\n- [[" % "]]")
                        (.similar-page-names
                         ps page_name))  ]
         (if (empty? sim-names) []
             [(common/package-card
               :similarly_name_pages :system :markdown ""
               (str "Here are some similarly named pages :"
                    (apply str sim-names)) false)]))
       })))



;; [schema-file (io/file (System/getProperty "user.dir") "clj_ts/gql_schema.edn")]
(def pagestore-schema
  (-> "gql_schema.edn"
      io/resource
      slurp

      edn/read-string

      (attach-resolvers {:resolve-source-page resolve-source-page
                         :resolve-page resolve-page
                         :resolve-card resolve-card
                         :resolve-text-search resolve-text-search
                         })
      schema/compile))


;; RecentChanges as RSS

(defn rss-recent-changes [link-fn]
  (let [ps (:page-store (server-state))
        make-link (fn [s]
                    (let [m (re-matches #"\* \[\[(\S+)\]\] (\(.+\))" s)
                          [pname date] [(second m) (nth m 2)]]
                      {:title (str pname " changed on " date)
                       :link (link-fn pname)}
                      ))
        rc (-> (.read-recentchanges ps)
               string/split-lines
               (#(map make-link %)))]
    (rss/channel-xml {:title "RecentChanges"
                      :link (-> (server-state) :site-url)
                      :description "Recent Changes in CardiganBay Wiki"}
                     rc
                     )))


;; Backlinks

(defn backlinks [page-name]
  (let [bl (.links-to (server-state) page-name)]
    (cond
      (= bl :not-available)
      (common/package-card
       :backlinks :system :markdown
       "Backlinks Not Available"
       "Backlinks Not Available"
       false)

      (= bl '())
      (common/package-card
       :backlinks :system :markdown
       "No Backlinks"
       "No Backlinks"
       false)

      :otherwise
      (ldb-query->mdlist-card
       "backlinks" "backlinks" "Backlinks" bl
       :calculated
       (fn [[a b]] (str "* [[" a "]] \n"))
       false))))



;; transforms on pages

(defn append-card-to-page! [page-name type body]
  (let [page-body (try
                    (pagestore/read-page (server-state) page-name)
                    (catch Exception e (str "Automatically created a new page : " page-name "\n\n"))
                    )
        new-body (str page-body "----
" type "
" body)]
    (write-page-to-file! page-name new-body )))

(defn prepend-card-to-page! [page-name type body]
  (let [page-body (try
                    (pagestore/read-page (server-state) page-name)
                    (catch Exception e (str "Automatically created a new page : " page-name "\n\n"))
                    )
        new-body (str
                      "----
" type "
" body "

----
"
                      page-body)
        ]
    (write-page-to-file! page-name new-body )))

(defn move-card [page-name hash destination-name]
  (if (= page-name destination-name) nil ;; don't try to move to self
      (let [ps (.page-store (server-state))
            from-cards (.get-page-as-card-maps ps page-name)
            card (common/find-card-by-hash from-cards hash)
            stripped (into [] (common/remove-card-by-hash from-cards hash))
            stripped_raw (common/cards->raw stripped)
            ]
        (println "===============================")
        (println "MOVING CARD "  )
        (println "CARD\n" card)
        (println "STRIPPED\n" stripped)
        (println "STRIPPED_RAW\n"  stripped_raw)
        (println "NOT NIL?" (not (nil? card)))
        (println "-=-=-=-=-=-=-=-=-=-=-=-=-=--=-=")
        (if (not (nil? card))
          (do
            (append-card-to-page! destination-name (:source_type card) (:source_data card))
            (write-page-to-file! page-name stripped_raw))))))

(defn reorder-card [page-name hash direction]
  (let [ps (.page-store (server-state))
        cards (.get-page-as-card-maps ps page-name)
        new-cards (if (= "up" direction)
          (common/move-card-up cards hash)
          (common/move-card-down cards hash))
        ]
    (write-page-to-file! page-name (common/cards->raw new-cards))))


;;;; Media and Custom files

(defn load-media-file [file-name]
  (-> (server-state) :page-store (.load-media-file file-name)))


(defn load-custom-file [file-name]
  (-> (server-state) :page-store (.load-custom-file file-name)))

;;file (io/file (System/getProperty "user.dir") (str "." uri))
