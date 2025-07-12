(ns clj-ts.views.card-gutter
  (:require [cljs.core.async :as a]
            [clojure.string :as str]
            [reagent.core :as r]
            [clj-ts.card :as cards]
            [clj-ts.events.rendering :as e-rendering]
            [clj-ts.http :as http]
            [clj-ts.navigation :as nav]
            [clj-ts.view :as view]
            [clj-ts.views.autocomplete-input :refer [autocomplete-input]]))

(defn clip-hash [from-page card]
  (let [hash-or-id (cards/->hash-or-id card)]
    (view/send-to-clipboard
     (str "----
{:card/type :transclude
 :from \"" from-page "\"
 :ids [\"" hash-or-id "\"]}\n"))))

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

(defn- toggle! [local-db]
  (if (= (-> @local-db :toggle) "none")
    (swap! local-db assoc :toggle "block")
    (swap! local-db assoc :toggle "none")))

(defn- clear-input! [local-db]
  (swap! local-db assoc :input-value nil))

(defn- on-clear-clicked [local-db]
  (clear-input! local-db))

(defn- on-navigate-clicked [db local-db card]
  (let [input-value (-> (or (:input-value @local-db) "")
                        (str/trim))]
    (when (not (str/blank? input-value))
      (<card-send-to-page! db card input-value))))

(defn- card-on-submit [card]
  (fn [db _local-db _e input-value]
    (<card-send-to-page! db card input-value)))

(defn- card-on-clicked [card]
  (fn [db _local-db _e name]
    (<card-send-to-page! db card name)))

(defn- card-on-key-up-enter [card]
  (fn [db _local-db _e name]
    (<card-send-to-page! db card name)))

(defn send-elsewhere-input [db local-db card]
  [:div.send-elsewhere-container
   [autocomplete-input {:db db
                        :local-db local-db
                        :placeholder "Send to another page"
                        :on-submit (card-on-submit card)
                        :on-clicked (card-on-clicked card)
                        :on-key-up-enter (card-on-key-up-enter card)
                        :class-name "send-elsewhere-input"
                        :container-class "send-elsewhere-input-container"}]
   [:div.send-elsewhere-input-actions
    (when (not (nil? (:input-value @local-db)))
      [:button
       {:on-click (fn [] (on-clear-clicked local-db))}
       [:span {:class [:material-symbols-sharp :clickable]} "close"]])
    (when (not (nil? (:input-value @local-db)))
      [:div.input-separator])
    [:button
     {:on-click (fn [] (on-navigate-clicked db local-db card))}
     [:span {:class [:material-symbols-sharp :clickable]} "navigate_next"]]]])

(defn card-gutter [_db _card]
  (let [local-db (r/atom {:toggle "none"
                          :input-value nil
                          :suggestions []
                          :autocomplete-visible? false})]
    (fn [db card]
      [:div.card-gutter
       [:div.actions-container
        [:div {:class    [:material-symbols-sharp :clickable]
               :on-click (fn [] (<card-reorder! db card "up"))}
         "expand_less"]
        [:div {:class    [:material-symbols-sharp :clickable]
               :on-click (fn [] (<card-reorder! db card "down"))}
         "expand_more"]
        [:span.expansion-toggle {:on-click (fn [] (toggle! local-db))}
         (if (= (-> @local-db :toggle) "none")
           [:span {:class [:material-symbols-sharp :clickable]} "expand_circle_down"]
           [:span {:class [:material-symbols-sharp :clickable]} "expand_circle_up"])]]
       (when-not (= "none" (:toggle @local-db))
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
            [:div.details-value.clickable {:on-click (fn [] (clip-hash (-> @db :current-page) card))}
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
             [send-elsewhere-input db local-db card]
             [:button.big-btn.reorder-bottom-button {:class    [:material-symbols-sharp :clickable]
                                                     :on-click (fn [] (<card-reorder! db card "end"))}
              "low_priority"]])])])))