(ns clj-ts.views.tool-bar
  (:require [clojure.string :as s]
            [cljs.core.async :as a]
            [reagent.core :as r]
            [clj-ts.events.editing :as e-editing]
            [clj-ts.mode :as mode]
            [clj-ts.page :as page]
            [clj-ts.transcript :as transcript]))

(defn- on-send-transcript-click [db page-name]
  (when (and (not (s/blank? page-name)) (not (s/blank? (:transcript @db))))
    (a/go
      (a/<! (page/<append-page! db page-name (:transcript @db)))
      (transcript/clear-transcript! db))))

(defn tool-bar [db db-mode db-current-page]
  (let [value (r/atom nil)
        on-clear-clicked (fn [] (reset! value nil))]
    (fn []
      (let [mode @db-mode]
        [:div.toolbar-container
         (condp = mode

           :editing
           [:div
            [:span.button-container
             [:button.big-btn.big-btn-left
              {:on-click (fn []
                           (mode/set-view-mode! db)
                           (page/cancel-editing! db))}
              [:span {:class [:material-symbols-sharp :clickable]} "close"]]
             [:button.big-btn.big-btn-right
              {:on-click (fn []
                           (mode/set-view-mode! db)
                           (page/<save-page! db))}
              [:span {:class [:material-symbols-sharp :clickable]} "save"]]]]

           :viewing
           [:span.button-container
            [:button.big-btn.big-btn-left
             {:on-click (fn []
                          (a/go
                            (when-let [response (a/<! (e-editing/<notify-global-editing-starting))]
                              (when (= response :ok)
                                (swap! db assoc :mode :editing)))))}
             [:span {:class [:material-symbols-sharp :clickable]} "edit"]]
            [:button.big-btn.big-btn-right
             [:a {:href (str "/api/exportpage?page=" @db-current-page)}
              [:span {:class [:material-symbols-sharp :clickable]} "deployed_code_update"]]]]

           :transcript
           [:span.button-container
            [:div#send-transcript-menu
             [:div.menu-outer
              [:div.menu-container
               [:input {:type        "text"
                        :placeholder "Send to another page"
                        :value       @value
                        :on-change   (fn [e] (reset! value (-> e .-target .-value)))}]
               [:div.send-transcript-input-actions
                [:button {:style {:visibility (if-not (s/blank? @value) "visible" "hidden")}
                          :on-click (fn [] (on-clear-clicked))}
                 [:span {:class [:material-symbols-sharp :clickable]} "close"]]
                [:div.input-separator {:style {:visibility (if-not (s/blank? @value) "visible" "hidden")}}]
                [:button
                 {:on-click (fn [] (on-send-transcript-click db @value))}
                 [:span {:class [:material-symbols-sharp :clickable]} "navigate_next"]]]]]]
            [:button.big-btn.big-btn-left
             {:on-click #(transcript/clear-transcript! db)}
             [:span {:class [:material-symbols-sharp :clickable]} "clear_all"]]
            [:button.big-btn.big-btn-right
             {:on-click #(transcript/exit-transcript! db)}
             [:span {:class [:material-symbols-sharp :clickable]} "close"]]])]))))
