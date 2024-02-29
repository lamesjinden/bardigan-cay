(ns clj-ts.views.card-bar
  (:require [cljs.core.async :as a]
            [clojure.string :as str]
            [reagent.core :as r]
            [clj-ts.http :as http]
            [clj-ts.navigation :as nav]
            [clj-ts.view :as view]))

(defn clip-hash [from-page hash]
  (view/send-to-clipboard
   (str "----
:transclude

{:from \"" from-page "\"
 :ids [\"" hash "\"] } ")))

(defn- <card-send-to-page! [db page-name hash new-page-name]
  (let [body (pr-str {:from page-name
                      :to   new-page-name
                      :hash hash})]
    (a/go
      (when-let [_ (a/<! (http/<http-post "/api/movecard" body))]
        (nav/<navigate! db new-page-name)))))

(defn- <card-reorder! [db page-name hash direction]
  (let [body (pr-str {:page      page-name
                      :hash      hash
                      :direction direction})]
    (a/go
      (when-let [response (a/<! (http/<http-post "/api/reordercard" body))]
        (nav/load-page-response db response)))))

(defn- toggle! [state]
  (if (= (-> @state :toggle) "none")
    (swap! state #(conj % {:toggle "block"}))
    (swap! state #(conj % {:toggle "none"}))))

(defn- clear-input! [input-value]
  (reset! input-value nil))

(defn- on-clear-clicked [^Atom input-value]
  (clear-input! input-value))

(defn- on-navigate-clicked [db input-value hash]
  (let [input-value (-> (or input-value "")
                        (clojure.string/trim))]
    (when (not (str/blank? input-value))
      (<card-send-to-page! db (-> @db :current-page) hash input-value))))

(defn send-elsewhere-input [db value hash]
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
     {:on-click (fn [] (on-navigate-clicked db @value hash))}
     [:span {:class [:material-symbols-sharp :clickable]} "navigate_next"]]]])

(defn card-bar [_db _card]
  (let [state (r/atom {:toggle "none"})
        input-value (r/atom nil)]
    (fn [db card]
      [:div.card-gutter
       [:div.actions-container
        [:div {:class    [:material-symbols-sharp :clickable]
               :on-click (fn [] (<card-reorder! db (-> @db :current-page) (get card "hash") "up"))}
         "expand_less"]
        [:div {:class    [:material-symbols-sharp :clickable]
               :on-click (fn [] (<card-reorder! db (-> @db :current-page) (get card "hash") "down"))}
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
          [send-elsewhere-input db input-value (get card "hash")]])])))