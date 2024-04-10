(ns clj-ts.views.card-gutter
  (:require [cljs.core.async :as a]
            [clojure.string :as str]
            [reagent.core :as r]
            [clj-ts.card :as cards]
            [clj-ts.events.rendering :as e-rendering]
            [clj-ts.http :as http]
            [clj-ts.navigation :as nav]
            [clj-ts.view :as view]))

(defn clip-hash [from-page hash]
  (view/send-to-clipboard
   (str "----
{:card/type :transclude
 :from \"" from-page "\"
 :ids [\"" hash "\"]}\n")))

(defn- <card-send-to-page! [db card new-page-name]
  (let [page-name (-> @db :current-page)
        hash (if-let [transcluded (get card "transcluded")]
               (get transcluded "hash")
               (get card "hash"))
        body (pr-str {:from page-name
                      :to   new-page-name
                      :hash hash})]
    (a/go
      (when-let [_ (a/<! (http/<http-post "/api/movecard" body))]
        (nav/<navigate! db new-page-name)))))

(defn- <card-reorder! [db card direction]
  (let [page-name (-> @db :current-page)
        hash (if-let [transcluded (get card "transcluded")]
               (get transcluded "hash")
               (get card "hash"))
        body (pr-str {:page      page-name
                      :hash      hash
                      :direction direction})]
    (a/go
      (when-let [response (a/<! (http/<http-post "/api/reordercard" body))]
        ;; reload the page content
        (nav/load-page-response db response)
        ;; wait for the next render of the parent component
        (a/<! (e-rendering/<notify-scroll-into-view page-name hash))
        ;; request that the newly rendered parent component be scrolled into view
        (cards/scroll-card-into-view card page-name)))))

(defn- toggle! [state]
  (if (= (-> @state :toggle) "none")
    (swap! state #(conj % {:toggle "block"}))
    (swap! state #(conj % {:toggle "none"}))))

(defn- clear-input! [input-value]
  (reset! input-value nil))

(defn- on-clear-clicked [^Atom input-value]
  (clear-input! input-value))

(defn- on-navigate-clicked [db input-value card]
  (let [input-value (-> (or input-value "")
                        (str/trim))]
    (when (not (str/blank? input-value))
      (<card-send-to-page! db card input-value))))

(defn send-elsewhere-input [db value card]
  [:div.send-elsewhere-input-container
   [:input.send-elsewhere-input {:type        "text"
                                 :placeholder "Send to another page"
                                 :value       @value
                                 :on-change   (fn [e] (reset! value (-> e .-target .-value)))}]
   [:div.send-elsewhere-input-actions
    (when (not (nil? @value))
      [:button
       {:on-click (fn [] (on-clear-clicked value))}
       [:span {:class [:material-symbols-sharp :clickable]} "close"]])
    (when (not (nil? @value))
      [:div.input-separator])
    [:button
     {:on-click (fn [] (on-navigate-clicked db @value card))}
     [:span {:class [:material-symbols-sharp :clickable]} "navigate_next"]]]])

(defn card-gutter [_db _card]
  (let [state (r/atom {:toggle "none"})
        input-value (r/atom nil)]
    (fn [db card]
      [:div.card-gutter
       [:div.actions-container
        [:div {:class    [:material-symbols-sharp :clickable]
               :on-click (fn [] (<card-reorder! db card "up"))}
         "expand_less"]
        [:div {:class    [:material-symbols-sharp :clickable]
               :on-click (fn [] (<card-reorder! db card "down"))}
         "expand_more"]
        [:span.expansion-toggle {:on-click (fn [] (toggle! state))}
         (if (= (-> @state :toggle) "none")
           [:span {:class [:material-symbols-sharp :clickable]} "expand_circle_down"]
           [:span {:class [:material-symbols-sharp :clickable]} "expand_circle_up"])]]
       (when-not (= "none" (:toggle @state))
         [:div.card-gutter-inner
          [:div.details-container
           [:div.details-pair
            [:div.details-label "id:"]
            [:div.details-value (get card "id")]]
           [:div.details-pair.right
            [:div.details-label "source:"]
            [:div.details-value (get card "source_type")]]
           [:div.details-pair
            [:div.details-label "hash:"]
            [:div.details-value.clickable {:on-click (fn [] (clip-hash (-> @db :current-page) (get card "hash")))}
             (get card "hash")]]
           [:div.details-pair.right
            [:div.details-label "render:"]
            [:div.details-value (get card "render_type")]]
           (when-let [source-page (get-in card ["transcluded" "source-page"])]
             [:div.details-pair
              [:div.details-label "page"]
              [:div.details-value source-page]])]
          (when (get card "user_authored?")
            [:div.card-gutter-toolbar
             [:button.big-btn.reorder-top-button {:class    [:material-symbols-sharp :clickable]
                                                  :on-click (fn [] (<card-reorder! db card "start"))}
              "low_priority"]
             [send-elsewhere-input db input-value card]
             [:button.big-btn.reorder-bottom-button {:class    [:material-symbols-sharp :clickable]
                                                     :on-click (fn [] (<card-reorder! db card "end"))}
              "low_priority"]])])])))