(ns clj-ts.client
  (:require
   [reagent.core :as r]
   [reagent.dom :as dom]
   [clojure.string :refer [lower-case trim]]
   [clojure.string :as string]
   [cljs.core.async :refer [<! timeout]]
   [cljs.core :refer [js->clj]]
   [cljs.reader :refer [read-string]]
   [cljs.pprint :refer [pprint]]


   [sci.core :as sci]
   [markdown.core :as md]


   [clj-ts.common :refer [raw-card->type-and-data
                          double-comma-table embed-boilerplate
                          double-bracket-links auto-links ]]
   ;;[clj-ts.common :refer [card->html ]]
            )
  (:import goog.net.XhrIo)
  (:require-macros [cljs.core.async.macros :refer [go]]))



;; State
(defonce db (r/atom
              {:current-page "HelloWorld"
               :raw ""
               :cards []
               :past ["HelloWorld"]
               :future []
               :wiki-name "Wiki Name"
               :site-url "Site URL"
               :editing false
               :mode :page
               :port 4545}))


;; PageStore


(defn load-page! [page-name new-past new-future]
  (let [lcpn page-name

        query (str "{\"query\" : \"query GetPage {
  source_page(page_name: \\\"" lcpn "\\\" ) {
    page_name
    body
  }
  server_prepared_page(page_name:  \\\"" lcpn "\\\") {
    page_name
    wiki_name
    site_url
    port
    ip
    cards {
      id
      hash
      source_type
      source_data
      render_type
      server_prepared_data
    }
    system_cards {
      id
      hash
      source_type
      source_data
      render_type
      server_prepared_data
    }
  }
} \",\"variables\":null, \"operationName\":\"GetPage\"}")]
    (.send XhrIo
           "/clj_ts/graphql"
           (fn [e]
             (let [status (-> e .-target .getStatusText)
                   edn (-> e .-target .getResponseText .toString
                           (#(.parse js/JSON %)) js->clj )
                   data (-> edn (get "data"))
                   raw (-> data (get "source_page") (get "body"))
                   cards (-> data (get "server_prepared_page") (get "cards"))
                   system-cards (-> data (get "server_prepared_page") (get "system_cards"))
                   site-url (-> data (get "server_prepared_page") (get "site_url"))
                   wiki-name (-> data (get "server_prepared_page") (get "wiki_name"))
                   port (-> data (get "server_prepared_page") (get "port"))
                   ip (-> data (get "server_prepared_page") (get "ip"))
                   ]

               (js/console.log "Cards " cards)
               (js/console.log "System Cards " system-cards)

               (swap! db assoc
                      :current-page page-name
                      :site-url site-url
                      :wiki-name wiki-name
                      :port port
                      :ip ip
                      :raw  raw
                      :cards cards
                      :system-cards system-cards
                      :past new-past
                      :future new-future)
               ))
           "POST",
           query )))

(defn generate-form-data [params]
  (let [form-data (js/FormData.)]
    (doseq [[k v] params]
      (.append form-data (name k) v))
    form-data))

(declare reload!)

(defn save-page! []
  (let [page-name (-> @db :current-page)
        new-data (-> js/document
                     (.getElementById "edit-field")
                     .-value)
        form-data (generate-form-data
                    {"page" page-name
                     "data" new-data})]
    (.send XhrIo
      "/clj_ts/save"
      (fn [e]
        (go
          (<! (timeout 1000))
          (reload!)
          (r/force-update-all)))
      "POST"
      (pr-str {:page page-name
               :data new-data}))))



;; Nav and History

(defn go-new! [p-name]
  (load-page! p-name (conj (-> @db :past) (-> @db :current-page))  []))

(defn forward! [p-name]
  (load-page! p-name (conj (-> @db :past) (-> @db :current-page)) (pop (-> @db :future)) )
  )

(defn reload! []
  (load-page! (:current-page @db) (-> @db :past) (-> @db :future)))

(defn back! []
  (load-page! (-> @db :past last) (pop (-> @db :past)) (conj (-> @db :future) (-> @db :current-page))  ))


;; Process page

(defn stamp! [stamp]
  (do
    (swap! db assoc
           :editing true
           :raw (str (-> @db :raw) "\n----\n:stamp\n" {:type stamp} ))))


(defn insert-text-at-cursor! [s]
  (let [ta (-> js/document
               (.getElementById "edit-field"))
        text (-> ta .-value)
        selectionStart (-> ta .-selectionStart)
        new (str
             (subs text 0 selectionStart)
             s
             (subs text selectionStart))
        ]
    (swap! db assoc :raw new)
    (-> ta (.-value) (set! new) )))

;; RUN

(let [start-page
      (.send XhrIo
      "/startpage"
      (fn [e]
        (-> e .-target .getResponseText .toString go-new!)))])



;; Rendering Views

(defn nav-input [value]
  [:input {:type "text"
           :id "navinputbox"
           :value @value
           :on-change #(reset! value (-> % .-target .-value))}])

(defn nav-bar []
  (let [current (r/atom (-> @db :future last))]
    (fn []
       (let [editing (-> @db :editing)]
         [:div {:class "navbar"}
          [:div {:class "breadcrumbs"}
           [:span (-> @db :wiki-name )]]
          [:div {:id "nav1"}

           [:span {:on-click (fn [] (go-new! "HelloWorld")) } "HelloWorld"]
           " || "
           [:span {:on-click (fn [] (go-new! "InQueue")) } "InQueue"]
           " || "
           [:span {:on-click (fn [] (go-new! "AboutThisWiki"))} "AboutThisWiki"]
           " || "
           [:span {:on-click (fn [] (go-new! "RecentChanges"))} "RecentChanges"]
           " || "
           [:span {:on-click (fn [] (go-new! "SandBox"))} "SandBox"]

           " || "
           [:a {:href "/api/exportallpages"} "Export All Pages"]


           ]
          [:div {:id "nav2"}
           [:button
            {:class "big-btn"
             :on-click (fn [] (back!))}
            [:img {:src "/icons/skip-back.png"}] " Back"]

           [:button
           {:class "big-btn"
             :on-click (fn [] (forward! (-> @db :future last)))} ""
           [:img {:src "/icons/skip-forward.png"}] " Forward"]

           [:button {:class "big-btn"}
            [:a {:href "/api/rss/recentchanges"} [:img {:src "/icons/rss.png"}]]]
           ]

          [:div {:id "nav3"}

           [nav-input current]
           [:button
            {:class "big-btn"
             :on-click (fn [] (go-new! @current))}
            [:img {:src "/icons/arrow-right.png"}] " Go!"]



           ]
          ] ))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn pastebar []
  [:span {:class "pastebar"}
   "+"


   [:button {:class "big-btn"
             :on-click
             (fn [e]
               (insert-text-at-cursor! "
----
:system

{:command :search
 :query \"\"
}

----"))}
    "Search"]

   [:button {:class "big-btn"
             :on-click
             (fn [e]
               (insert-text-at-cursor! "
----
:evalclient

;; CODE GOES HERE

----"))}
    "Code"]


   [:button {:class "big-btn"
             :on-click
             (fn [e]
               (insert-text-at-cursor! (embed-boilerplate :youtube)))}
    "YouTube"]

   [:button {:class "big-btn"
             :on-click
             (fn [e]
               (insert-text-at-cursor! (embed-boilerplate :soundcloud)))}
    "SoundCloud"]

   [:button {:class "big-btn"
             :on-click
             (fn [e]
               (insert-text-at-cursor! (embed-boilerplate :bandcamp)))}
    "BandCamp"]


   [:button {:class "big-btn"
             :on-click
             (fn [e]
               (insert-text-at-cursor! (embed-boilerplate :twitter)))}
    "Twitter"]
   ])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn tool-bar []
  (let [current (r/atom (-> @db :future last))]
    (fn []
      (let [editing (-> @db :editing)]
        [:div
         (if editing
           [:div
            [:div
             [:span
              [:button {:class "big-btn"
                        :on-click
                        (fn []
                          (do
                            (swap! db assoc :editing (not editing))
                            (reload!)))}
               [:img {:src "/icons/x.png"}] " Cancel"]
              [:button {:class "big-btn"
                        :on-click
                        (fn []
                          (do
                            (swap! db assoc :editing (not editing))
                            (save-page!)) )}
               [:img {:src "/icons/save.png"}] " Save"]




              ]]
            (pastebar)

            ]

           [:span
            [:button {:class "big-btn"
                      :on-click
                      #(swap! db assoc :editing (not editing))}
             [:img {:src "/icons/edit.png"}] " Edit"]

            [:button {:class "big-btn"}
             [:a {:href (str "/api/exportpage?page=" (-> @db :current-page))}
              [:img {:src "/icons/package.png"}]
              " Export"]]



            ])


         (comment
           " :: Stamps :: "
           [:button {:class "big-btn"
                     :on-click
                     (fn []
                       (stamp! :delete ))} "Delete"]
           " | "
           [:button {:class "big-btn"
                     :on-click
                     (fn []
                       (stamp! :fix)) } "Fix"])]))
    ))


(defn not-blank? [card]
  (not= "" (string/trim (get card "source_data")))
  )

(defn card->html [card]
  (-> (get card "server_prepared_data")
      (double-comma-table)
      (md/md->html)
      (auto-links)
      (double-bracket-links)))




(defn card-bar [card]
  (let [meta-id  (str "cardmeta" (get card "hash") )
        state (r/atom {:toggle "none"})
        toggle! (fn [e]
                  (do
                    (if (= (-> @state :toggle) "none")
                      (swap! state #(conj % {:toggle "block"}) )
                      (swap! state #(conj % {:toggle "none"})))))]
    (fn [card]
      [:div {:class :card-meta}
       [:span {:on-click toggle! :style {:size "smaller" :float "right"}}
        (if (= (-> @state :toggle) "none")
          [:img {:src "/icons/eye.png"}]
          [:img {:src "/icons/eye-off.png"}]
          )

        ]
       [:div {:id meta-id :style {:spacing-top "5px" :display (-> @state :toggle)
                                  }}
        [:div [:h4 "Card Bar"]]
        [:div
         [:span "ID: " (get card "id")] " | Hash: "
         [:span (get card "hash")] " | Source type: "
         [:span (get card "source_type")] " | Render type: "
         [:span (get card "render_type")]]

        [:div
         [:form {:action "/api/movecard"}
          "Send to Another Page : "
          [:input { :name "to"}]
          [:input { :name "hash" :type "hidden" :value (get card "hash")}]
          [:input { :name "from" :type "hidden" :value (-> @db :current-page )}]
          [:img {:src "/icons/send.png"}]  [:input { :type "submit" :value "Send"}]
          ]
         [:form {:action "/api/reordercard"}
          [:input {:name "direction" :type "hidden" :value "up"}]
          [:input {:name "page" :type "hidden" :value (-> @db :current-page)}]
          [:input { :name "hash" :type "hidden" :value (get card "hash")}]
          [:img {:src "/icons/chevrons-up.png"}]
          [:input {:type "submit" :value "Up"}]]
         [:form {:action "/api/reordercard"}
          [:input {:name "direction" :type "hidden" :value "down"}]
          [:input { :name "hash" :type "hidden" :value (get card "hash")}]
          [:input {:name "page" :type "hidden" :value (-> @db :current-page)}]
          [:img {:src "/icons/chevrons-down.png"}]
          [:input {:type "submit" :value "Down"}]]
         ]


        ]
       ])))




(defn workspace [card]
  (let [state (r/atom {:code-toggle true
                       :calc-toggle false
                       :result-toggle true
                       :code (get card "server_prepared_data")
                       :calc []
                       :result ""})


        id (str "ws" (get card "hash"))
        code-id (str id "-code")
        calc-id (str id "-calc")
        result-id (str id "-result")

        toggle-code!
        (fn [e]
          (js/console.log (str "Toggle code " (-> @state :code-toggle)))

          (swap! state #(conj % {:code-toggle (-> @state :code-toggle not)})))

        toggle-calc!
        (fn [e]
          (js/console.log (str "Toggle calc " (-> @state :calc-toggle)) )
          (swap! state #(conj % {:calc-toggle (-> @state :calc-toggle not)})))

        toggle-result!
        (fn [e]
          (js/console.log "Toggle result "  (-> @state :result-toggle))
          (swap! state #(conj % {:result-toggle (-> @state :result-toggle not)})))

        display
        (fn [d]
          (if d "block" "none"))

        execute-code
        (fn [e]
          (let [result (sci/eval-string (-> @state :code))]
            (swap! state #(conj % {:calc result :result result})))
          )
        ]

    (fn [card]
      (let []
        [:div {:class :workspace}
         [:h3 "Workspace"]
         [:p {:class :workspace-note} [:i "Note : this is a ClojureScript workspace based on "
                 [:a {:href "https://github.com/borkdude/sci"} "SCI"]
                 ". Be aware that it does not save any changes you make in the textbox.

You'll need to  edit the page fully to make permanent changes to the code. "]]
         [:div {:class :workspace-buttons}
          [:button {:class :workspace-button :on-click execute-code} "Run"]
          [:button {:class :workspace-button :on-click toggle-code!} "Code"]
          [:button {:class :workspace-button :on-click toggle-calc!} "Calculated"]
          [:button {:class :workspace-button :on-click toggle-result!} "Output"]]
         [:div {:class :code :style {:padding "3px"
                                     :display (display (-> @state :code-toggle))} }
          [:h4 "Source"]
          [:textarea {:cols 60 :rows 10
                      :on-change
                      (fn [e] (swap! state #(conj % {:code (-> e .-target .-value )})))}
           (trim (-> @state :code))]]
         [:div {:class :calculated-out :style {:padding "3px"
                                               :display (display (-> @state :calc-toggle))}}
          [:h4 "Calculated"]
          [:pre
           (with-out-str (pprint (str (-> @state :calc))))
           ]]
         [:div {:class :results :style {:padding "3px"
                                        :display (display (-> @state :result-toggle))}}
          [:h4 "Result"]
          [:div
           (let [result (-> @state :result)]
             (cond

               (number? result)
               result

               (string? result)
               result

               (= (first result) :div)
               result

               :else
               (str result)))] ]
         ]))))


(defn card-top-bar [card]

  )

(defn one-card [card]
  (let [
        inner-html
        (fn [s] [:div {:dangerouslySetInnerHTML {:__html s}}])

        state2 (r/atom {:toggle "block"})

        toggle!
        (fn [e]
          (do
            (if (= (-> @state2 :toggle) "none")
              (swap! state2 #(conj % {:toggle "block"}) )
              (swap! state2 #(conj % {:toggle "none"})))))

        ]
    ;;(js/console.log (pr-str card))

    (fn [card]
      (let [rtype (get card "render_type")
            data (get card "server_prepared_data")
            inner
            (condp = rtype

              ":raw"
              (inner-html (str "<pre>" data "</pre>"))

              ":markdown"
              (inner-html (card->html card))

              ":html"
              (inner-html (str data))

              ":stamp"
              (inner-html (str data))

              ":hiccup"
              "THIS SHOULD BE HICCUP RENDERED"

              ":workspace"
              [workspace card]

              (str "UNKNOWN TYPE(" type ") " data))

            ]
        [:div {:class :card-outer}

         [:div {:class :card-meta}
          [:span {:on-click toggle! :style {:size "smaller" :float "right"}}
           (if (= (-> @state2 :toggle) "none")
             [:img {:src "/icons/maximize-2.svg"}]
             [:img {:src "/icons/minimize-2.svg"}]
             )]]

         [:div
          {:style {:spacing-top "5px"
                   :display (-> @state2 :toggle)}}
          [:div
           {:class "card"
            :on-click
            (fn [e]
              (let [tag (-> e .-target)
                    classname (.getAttribute tag "class")
                    data (.getAttribute tag "data")
                    x (-> @db :dirty)]
                (if (= classname "wikilink")
                  (go-new! data))))
            }

           inner ]]
         [card-bar card]
         ]))))



(defn card-list []
  [:div
   [:div
    (try
      (let [cards (-> @db :cards)]
        (for [card (filter not-blank? cards)]

          (try
            [one-card card]
            (catch :default e
              [:div {:class :card-outer}
               [:div {:class "card"}
                [:h4 "Error"]
                (str e)]]))
          )
        )
      (catch :default e
        (do
          (js/console.log "ERROR")
          (js/console.log (str e))
          (js/alert e))))

    ]
   [:div
    (try
      (let [cards (-> @db :system-cards)]
        (for [card cards]
          [one-card card]
          )
        )
      (catch :default e
        (js/alert e)))]
   ])




(defn main-container []

  [:div
   (if (= :page (-> @db :mode ))
     [:div
      (if (-> @db :editing)
        [:div {:class "edit-box"}
         [:textarea
          {:id "edit-field" :cols 80 :rows 40
           :on-key-press
           (fn [e]
             (js/console.log "KEYPRESS ON TEXTAREA")
             (let [kc (.-charCode e)]
               (js/console.log "pressed " kc)
               (if (-> @db :editing)
                 (cond
                   (and (.-ctrlKey e) (= 81))
                   (insert-text-at-cursor! "THIS IS INSERTED")
                   :else '())
                 (if (and (.-ctrlKey e) (= 69 kc))
                   (swap! db assoc
                          :editing true)
                   (-> js/document (.getElementById "edit-field") (.focus) )))))
           }
          (-> @db :raw)]]
        [:div
         [card-list]]
        )]
     [:div
      [:h2 "Bookmark"]])])

;;

; Main page
(defn content []
  [:div {:class "main-container"}
   [:div {:class "headerbar"}
    [:div
     [:div [nav-bar]]

     ]]
   [:div {:class "context-box"}

      [:h2 (-> @db :current-page)
       [:span {:class "tslink"}
        [:a {:href (str "http://thoughtstorms.info/view/" (-> @db :current-page))} "(TS)" ]] ]

      [:div [tool-bar]]
      [main-container]]
   [:div {:class "footer"}
    [:span
     [:span "This " (-> @db :wiki-name) " wiki!"]
     [:span " || Home : " [:a {:href (-> @db :site-url)} (-> @db :site-url)] " || " ]
     [:span [:a {:href "/api/system/db"} "DB"] " || "]
     [:a {:href "https://github.com/interstar/cardigan-bay"} "Cardigan Bay "]
     "(c) Phil Jones 2020  || "
     [:span "IP: "(str (-> @db :ip) ) " || "]
     [:a {:href
          (str "javascript:(function(){window.location='http://localhost:" (-> @db :port) "/api/bookmarklet?url='+document.URL;})();")} "Bookmark to this Wiki"]] ]])


;; tells reagent to begin rendering




(r/render-component [content]
                   (.querySelector js/document "#app"))





(js/document.addEventListener
 "keypress"
 (fn [e]
   (js/console.log "KEYPRESS EVENT")
   (let [kc (.-charCode e)]

     (js/console.log "pressed " (.-charCode e))

     )))
