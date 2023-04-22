(ns clj-ts.views.card-bar
  (:require [reagent.core :as r]
            [clj-ts.handle :as handle]
            [clj-ts.events.navigation :as nav]
            [clj-ts.view :as view]
            [promesa.core :as p]))

(defn clip-hash [from-page hash]
  (view/send-to-clipboard
    (str "----
:transclude

{:from \"" from-page "\"
 :ids [\"" hash "\"] } ")))

(defn on-save-clicked-async! [db card]
  (swap! db assoc :mode :viewing)
  (handle/save-card-async!
    (-> @db :current-page)
    (get card "hash")
    (get card "source_type")
    (-> js/document
        (.getElementById (str "edit-" (get card "hash")))
        .-value)))

(defn toggle! [state text-val card]
  (if (= (-> @state :toggle) "none")
    (do
      (swap! state #(conj % {:toggle "block"}))
      (reset! text-val (get card "source_data")))
    (swap! state #(conj % {:toggle "none"}))))

(defn card-bar [card]
  (let [meta-id (str "cardmeta" (get card "hash"))
        state (r/atom {:toggle "none"})
        send-value (r/atom "")
        text-value (r/atom (get card "source_data"))        ;; Edit box
        toggle-fn! (fn [] (toggle! state text-value card))]
    (fn [db card]
      [:div {:class :card-meta}
       [:div
        [:span {:on-click (fn [] (handle/card-reorder-async!
                                   db
                                   (-> @db :current-page)
                                   (get card "hash")
                                   "up"))}
         [:img {:src "/icons/chevrons-up.png"}]]
        [:span {:on-click (fn [] (handle/card-reorder-async!
                                   db
                                   (-> @db :current-page)
                                   (get card "hash")
                                   "down"))}
         [:img {:src "/icons/chevrons-down.png"}]]
        [:span {:on-click toggle-fn!
                :style    {:size "smaller" :float "right"}}
         (if (= (-> @state :toggle) "none")
           [:img {:src "/icons/eye.png"}]
           [:img {:src "/icons/eye-off.png"}])]]
       [:div {:id    meta-id
              :class :card-bar
              :style {:spacing-top "5px" :display (-> @state :toggle)}}
        [:div [:h3 "Card Bar"]]
        [:div
         [:span "ID: " (get card "id")] " | Hash: "
         [:span {:class "mini-button" :on-click (fn [] (clip-hash (-> @db :current-page)
                                                                  (get card "hash")))}
          "Hash: " (get card "hash")] " | Source type: "
         [:span (get card "source_type")] " | Render type: "
         [:span (get card "render_type")]]
        [:hr]
        [:div {:class :send-to-bar}
         [:h4 "Send to Another Page"]
         [:div {:class :send-to-inner}
          [:input {:name "hash" :id "sendhash" :type "hidden" :value (get card "hash")}]
          [:input {:name "from" :id "sendcurrent" :type "hidden" :value (-> @db :current-page)}]
          [:input {:type      "text"
                   :id        "sendto-inputbox"
                   :value     @send-value
                   :on-change #(reset! send-value (-> % .-target .-value))}]
          [:button {:on-click
                    (fn []
                      (handle/card-send-to-page-async!
                        db
                        (-> @db :current-page)
                        (get card "hash")
                        @send-value))} "Send"]]]
        [:hr]
        [:div
         [:h4 "Edit Card"]
         [:div
          [:span
           [:button {:class    "big-btn"
                     :on-click (fn []
                                 (-> (nav/reload-async! db)
                                     (p/then toggle-fn!)))}
            [:img {:src "/icons/x.png"}] " Cancel"]
           [:button {:class    "big-btn"
                     :on-click (fn []
                                 (-> (on-save-clicked-async! db card)
                                     (p/then toggle-fn!)))}
            [:img {:src "/icons/save.png"}] " Save"]]]
         [:div
          [:textarea {:id        (str "edit-" (get card "hash"))
                      :rows      10
                      :width     "100%"
                      :value     @text-value
                      :on-change #(reset! text-value (-> % .-target .-value))}]]]]])))