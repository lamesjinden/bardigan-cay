(ns clj-ts.views.nav-bar
  (:require [cljs.core.async :as a]
            [clojure.string :as str]
            [reagent.core :as r]
            [sci.core :as sci]
            [clj-ts.http :as http]
            [clj-ts.keyboard :as keyboard]
            [clj-ts.mode :as mode]
            [clj-ts.navigation :as nav]
            [clj-ts.view :as view]
            [clj-ts.views.app-menu :refer [app-menu]]))

;; region input

(defn- clear-input! [input-value]
  (reset! input-value nil))

(defn- on-clear-clicked [^Atom input-value]
  (clear-input! input-value))

(defn nav-input-on-key-enter [db e]
  (let [key-code (.-keyCode e)
        input-value (-> e .-target .-value str/trim)
        page-name input-value]
    (when (and (= key-code keyboard/key-enter-code)
               (seq input-value))
      (nav/<navigate! db page-name))))

;; endregion

;; region search

(defn- updated-transcript [code result transcript]
  (str "<p> > " code "\n<br/>\n" result "\n</p>\n" transcript))

(defn- prepend-transcript! [db code result]
  (let [current-transcript (-> @db :transcript)
        updated-transcript (updated-transcript code result current-transcript)]
    (swap! db assoc :transcript updated-transcript)
    (mode/set-transcript-mode! db)))

(defn- load-search-results! [db cleaned-query body]
  (let [edn (js->clj body)
        result (get edn "result_text")]
    (prepend-transcript! db
                         (str "Searching for " cleaned-query)
                         (view/string->html result))))

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
    (prepend-transcript! db code result)))

(defn- on-eval-clicked [db input-value]
  (let [current (-> (or input-value "")
                    (str/trim))]
    (when (not (str/blank? current))
      (eval-input! db current))))

(defn- on-link-click [db e target aux-clicked?]
  (.preventDefault e)
  (cond
    (= target "Transcript")
    (swap! db assoc :mode :transcript)

    :else
    (nav/<on-link-clicked db e target aux-clicked?)))

;; endregion

(defn nav-input [db value]
  [:input {:type        "text"
           :class       :nav-input-text
           :value       @value
           :on-change   #(reset! value (-> % .-target .-value))
           :on-key-up   #(nav-input-on-key-enter db %)
           :placeholder "Navigate, Search, or Eval"}])

(defn nav-bar [db db-nav-links]
  (let [input-value (r/atom nil)]
    (fn []
      (let [nav-links @db-nav-links]
        [:div.nav-container
         [:nav#header-nav
          (->> nav-links
               (mapcat #(vector [:a.clickable {:key          %
                                               :on-click     (fn [e] (on-link-click db e % false))
                                               :on-aux-click (fn [e] (on-link-click db e % true))
                                               :href         (str "/pages/" %)} %])))
          [app-menu db (r/cursor db [:theme])]]
         [:div#header-input
          [nav-input db input-value]
          [:div.header-input-actions
           (when (not (nil? @input-value))
             [:button#close-button.header-input-button
              {:on-click (fn [] (on-clear-clicked input-value))}
              [:span {:class [:material-symbols-sharp :clickable]} "close"]])
           (when (not (nil? @input-value))
             [:div.header-input-separator])
           [:button#go-button.header-input-button
            {:on-click (fn [] (on-navigate-clicked db @input-value))}
            [:span {:class [:material-symbols-sharp :clickable]} "navigate_next"]]
           [:button.header-input-button
            {:on-click (fn [] (on-search-clicked db @input-value))}
            [:span {:class [:material-symbols-sharp :clickable]} "search"]]
           [:button#lambda-button.header-input-button
            {:on-click (fn [] (on-eval-clicked db @input-value))}
            [:span {:class [:material-symbols-sharp :clickable]} "λ"]]]]]))))
