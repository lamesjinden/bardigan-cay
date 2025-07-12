(ns clj-ts.views.nav-bar
  (:require [cljs.core.async :as a]
            [clojure.string :as str]
            [reagent.core :as r]
            [sci.core :as sci]
            [clj-ts.events.transcript :as transcript-events]
            [clj-ts.http :as http]
            [clj-ts.navigation :as nav]
            [clj-ts.transcript :as transcript]
            [clj-ts.view :as view]
            [clj-ts.views.app-menu :refer [app-menu]]
            [clj-ts.views.autocomplete-input :refer [autocomplete-input]]))

;; region nav input handlers

(defn- nav-on-submit [db _local-db _e input-value]
  (nav/<navigate! db input-value))

(defn- nav-on-clicked [db _local-db _e name]
  (nav/<navigate! db name))

(defn- nav-on-key-up-enter [db _local-db _e name]
  (nav/<navigate! db name))

;; endregion

;; region search

(defn- load-search-results! [db cleaned-query body]
  (let [edn (js->clj body)
        result (get edn "result_text")]
    (transcript/prepend-transcript! db
                                    (str "Searching for " cleaned-query)
                                    (view/string->html result))
    (transcript-events/<notify-transcript-navigating db)))

(defn- search-text-async! [db query-text]
  (let [cleaned-query (-> (or query-text "")
                          (str/replace "\"" "")
                          (str/replace "'" "")
                          (str/trim)
                          (js/encodeURI))]
    (when (not (str/blank? cleaned-query))
      (a/go
        (when-let [result (a/<! (http/<http-get (str "/api/search?q=" cleaned-query)))]
          (let [{body-text :body} result
                body (.parse js/JSON body-text)]
            (load-search-results! db cleaned-query body)))))))

(defn- on-search-clicked [db query-text]
  (let [query-text (-> (or query-text "")
                       (str/trim))]
    (when (not (str/blank? query-text))
      (search-text-async! db query-text))))

(defn- on-navigate-clicked [db input-value]
  (let [input-value (-> (or input-value "")
                        (str/trim))]
    (when (not (str/blank? input-value))
      (nav/<navigate! db input-value))))

;; endregion

;; region eval

(defn- eval-input! [db input-value]
  (let [code input-value
        result (sci/eval-string code)]
    (transcript/prepend-transcript! db code result)
    (transcript-events/<notify-transcript-navigating db)))

(defn- on-eval-clicked [db input-value]
  (let [current (-> (or input-value "")
                    (str/trim))]
    (when (not (str/blank? current))
      (eval-input! db current))))

(defn- on-link-click [db e target aux-clicked?]
  (.preventDefault e)
  (nav/<on-link-clicked db e target aux-clicked?))

(defn- on-transcript-click [db e]
  (.preventDefault e)
  (transcript-events/<notify-transcript-navigating db))

;; endregion


(defn nav-bar [_db _db-nav-links]
  (let [local-db (r/atom {:input-value nil
                          :suggestions []
                          :autocomplete-visible? false})]
    (fn [db db-nav-links]
      (let [nav-links @db-nav-links]
        [:div.nav-container
         [:nav#header-nav
          (->> nav-links
               (remove #(= % "Transcript"))
               (mapcat #(vector [:a.clickable {:key          %
                                               :on-click     (fn [e] (on-link-click db e % false))
                                               :on-aux-click (fn [e] (on-link-click db e % true))
                                               :href         (str "/pages/" %)} %])))
          [:a.clickable {:key "transcript"
                         :on-click (fn [e] (on-transcript-click db e))} "Transcript"]
          [app-menu db (r/cursor db [:theme])]]
         [:div#header-input
          [autocomplete-input {:db db
                               :local-db local-db
                               :placeholder "Navigate, Search, or Eval"
                               :on-submit nav-on-submit
                               :on-clicked nav-on-clicked
                               :on-key-up-enter nav-on-key-up-enter
                               :class-name "nav-input-text"
                               :container-class "nav-input-container"
                               :container-selector "#header-input"}]
          [:div.header-input-actions
           (when (not (nil? (:input-value @local-db)))
             [:button#close-button.header-input-button
              {:on-click (fn [] (swap! local-db assoc :input-value nil))}
              [:span {:class [:material-symbols-sharp :clickable]} "close"]])
           (when (not (nil? (:input-value @local-db)))
             [:div.header-input-separator])
           [:button#go-button.header-input-button
            {:on-click (fn [] (on-navigate-clicked db (:input-value @local-db)))}
            [:span {:class [:material-symbols-sharp :clickable]} "navigate_next"]]
           [:button.header-input-button
            {:on-click (fn [] (on-search-clicked db (:input-value @local-db)))}
            [:span {:class [:material-symbols-sharp :clickable]} "search"]]
           [:button#lambda-button.header-input-button
            {:on-click (fn [] (on-eval-clicked db (:input-value @local-db)))}
            [:span {:class [:material-symbols-sharp :clickable]} "λ"]]]]]))))
