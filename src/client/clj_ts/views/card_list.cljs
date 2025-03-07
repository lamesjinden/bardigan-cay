(ns clj-ts.views.card-list
  (:require [reagent.core :as r]
            [clj-ts.card :as cards]
            [clj-ts.view :as view]
            [clj-ts.views.inner-html-card :refer [inner-html]]
            [clj-ts.views.card-shell :refer [card-shell]]
            [clj-ts.views.deadline-card :refer [deadline]]
            [clj-ts.views.lazy-graph :refer [suspended-graph-card-component]]
            [clj-ts.views.lazy-workspace-card :refer [suspended-workspace-component]]
            [clj-ts.views.manual-copy-card :refer [manual-copy]]
            [clj-ts.views.todo-card :refer [todo]]))

(defn error-boundary
  [& _children]
  (let [err-state (r/atom nil)]
    (r/create-class
     {:display-name        "ErrorBoundary"
      :component-did-catch (fn [err info]
                             (reset! err-state [err info]))
      :reagent-render      (fn [& children]
                             (if (nil? @err-state)
                               (into [:<>] children)
                               (let [[_ info] @err-state]
                                 [:pre.error-boundary
                                  [:code (pr-str info)]])))})))

(defn card->component [db card]
  (let [render-type (get card "render_type")
        data (get card "server_prepared_data")
        inner-component (condp = render-type

                          "markdown"
                          [inner-html (view/card->html card)]

                          "manual-copy"
                          [manual-copy card]

                          "raw"
                          [inner-html (str "<pre>" data "</pre>")]

                          "code"
                          [inner-html (str "<code>" data "</code>")]

                          "workspace"
                          (suspended-workspace-component {:db db :card card})

                          "todo"
                          [todo db card]

                          "deadline"
                          [deadline db card]

                          "graph"
                          (suspended-graph-card-component {:db db :card card})

                          "html"
                          [inner-html data]

                          "hiccup"
                          [data]

                          (str "UNKNOWN TYPE ( " render-type " ) " data))]
    inner-component))

(defn error-card [exception]
  {"render_type"          "hiccup"
   "server_prepared_data" [:div
                           [:h4 "Error"]
                           [:div (str exception)]
                           [:div (.-stack exception)]]})

(defn card-list [db db-cards db-system-cards]
  (fn [_this]
    (let [current-page (:current-page @db)
          key-fn (fn [card] (cards/->key card current-page))]
      [:<>
       [:div.user-card-list
        (let [cards @db-cards]
          (for [card (filter view/not-blank? cards)
                :let [key (key-fn card)]]
            [:div.user-card-list-item {:key key
                                       :data-card-id key}
             (try
               [card-shell db card
                [error-boundary
                 [card->component db card]]]
               (catch :default e
                 (let [error-card (error-card e)]
                   [card-shell db error-card
                    [error-boundary
                     [card->component db error-card]]])))]))]
       [:div.system-card-list
        (try
          (let [system-cards @db-system-cards]
            (for [system-card system-cards]
              [:div.system-card-list-item {:key key}
               [card-shell db system-card
                [error-boundary
                 [card->component db system-card]]]]))
          (catch :default e
            (let [error-card (error-card e)]
              [card-shell db error-card
               [error-boundary
                [card->component db error-card]]])))]])))