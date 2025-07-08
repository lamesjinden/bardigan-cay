(ns clj-ts.views.nav-bar
  (:require [cljs.core.async :as a]
            [clojure.string :as str]
            [reagent.core :as r]
            [sci.core :as sci]
            [clj-ts.events.transcript :as transcript-events]
            [clj-ts.http :as http]
            [clj-ts.keyboard :as keyboard]
            [clj-ts.navigation :as nav]
            [clj-ts.transcript :as transcript]
            [clj-ts.view :as view]
            [clj-ts.views.app-menu :refer [app-menu]]
            [clj-ts.autocomplete.autocomplete-process :as autocomplete]))

;; region input

(defn nav-input-on-key-enter [db e]
  (let [input-value (-> e .-target .-value str/trim)
        page-name input-value]
    (when (seq input-value)
      (nav/<navigate! db page-name))))

(defn nav-input-on-escape [_db local-db _e]
  (if (:autocomplete-visible? @local-db)
    (swap! local-db assoc :autocomplete-visible? false)
    (swap! local-db assoc :input-value nil)))

(defn focus-autocomplete-element [_db local-db]
  (let [autocomplete-visible? (:autocomplete-visible? @local-db)
        autocomplete-element (:autocomplete-element @local-db)]
    (when (and autocomplete-visible? autocomplete-element)
      (.focus autocomplete-element)
      (swap! local-db assoc :selected-index 0))))

(defn nav-input-on-key-up [db local-db e]
  (let [key-code (.-keyCode e)]
    (condp = key-code
      keyboard/key-enter-code (nav-input-on-key-enter db e)
      keyboard/key-escape-code (nav-input-on-escape db local-db e)
      keyboard/key-down-code (focus-autocomplete-element db local-db)
      nil)))

(defn autocomplete-on-key-escape [_db local-db _e]
  (swap! local-db assoc :autocomplete-visible? false))

(defn autocomplete-on-key-enter [db local-db _e]
  (when-let [selected-index (:selected-index @local-db)]
    (let [{:keys [name] :as _selected-suggestion} (get-in @local-db [:suggestions selected-index])]
      (when name
        (nav/<navigate! db name)
        (swap! local-db assoc :autocomplete-visible? false)))))

(defn autocomplete-on-key-up-arrow [_db local-db e]
  (.preventDefault e)
  (when-let [selected-index (:selected-index @local-db)]
    (let [next-index (max (dec selected-index) 0)]
      (swap! local-db assoc :selected-index next-index))))

(defn autocomplete-on-key-down-arrow [_db local-db e]
  (.preventDefault e)
  (when-let [selected-index (:selected-index @local-db)]
    (let [suggestions (:suggestions @local-db)
          next-index (min (inc selected-index) (dec (count suggestions)))]
      (swap! local-db assoc :selected-index next-index))))

(defn autocomplete-on-key-up [db local-db e]
  (let [key-code (.-keyCode e)]
    (condp = key-code
      keyboard/key-escape-code (autocomplete-on-key-escape db local-db e)
      keyboard/key-enter-code (autocomplete-on-key-enter db local-db e)
      keyboard/key-down-code (autocomplete-on-key-down-arrow db local-db e)
      keyboard/key-up-code (autocomplete-on-key-up-arrow db local-db e)
      nil)))

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

(defn nav-input [db local-db]
  [:input {:type        "text"
           :class       :nav-input-text
           :value       (:input-value @local-db)
           :on-change   #(swap! local-db assoc :input-value (-> % .-target .-value))
           :on-key-up   #(nav-input-on-key-up db local-db %)
           :placeholder "Navigate, Search, or Eval"}])

(defn autocomplete-dropdown [db local-db]
  (let [{:keys [suggestions autocomplete-visible?]} @local-db
        search-results (map (fn [{:keys [name]}] name) suggestions)]
    (when (and autocomplete-visible?
               (seq search-results))
      [:ul.autocomplete-dropdown {:tab-index 0
                                  :on-key-up   #(autocomplete-on-key-up db local-db %)
                                  :ref (fn [element] (swap! local-db assoc :autocomplete-element element))}
       (->> (map-indexed (fn [i result]
                           ^{:key result}
                           [:li.autocomplete-suggestion
                            {:tab-index 0
                             :data-index i
                             :class (or (when (= i (:selected-index @local-db)) "selected") "")
                             :on-click #(do (nav/<navigate! db result)
                                            (swap! local-db assoc :autocomplete-visible? false))}
                            result])
                         search-results)
            (doall))])))

(defn nav-bar [_db _db-nav-links]
  (let [local-db (r/atom {:input-value nil
                          :suggestions []
                          :autocomplete-visible? false})
        autocomplete-input$ (atom nil)
        autocomplete-output$ (atom nil)
        click-listener (atom nil)
        track-input-changed (atom nil)
        results-process (atom nil)]

    (r/create-class
     {:component-did-mount
      (fn [_this]
        ;; Initialize autocomplete process
        (let [raw-input$ (a/chan 1 (comp
                                    (map (fn [term] (str/trim term)))
                                    (filter (fn [term] (not (str/blank? term))))
                                    (autocomplete/distinct-until-changed-transducer)
                                    (autocomplete/query-length-filter-transducer 3)))
              debounced$ (autocomplete/create-debounced-channel raw-input$ 300)
              output$ (autocomplete/<create-autocomplete-process debounced$)]
          (reset! autocomplete-input$ debounced$)
          (reset! autocomplete-output$ output$)
          ;; Store reference to raw input channel for cleanup
          (swap! local-db assoc :raw-input$ raw-input$))

        ;; Set up input tracking with r/track! - bridge reagent change stream onto core.async channel
        (reset! track-input-changed
                (r/track! (fn []
                            (when-let [value (:input-value @local-db)]
                              (when-let [raw-input$ (:raw-input$ @local-db)]
                                (a/put! raw-input$ value))))))

        ;; Start results processing loop
        (reset! results-process
                (a/go-loop []
                  (when-some [result (a/<! @autocomplete-output$)]
                    (let [{:keys [_query suggestions result-error]} result]
                      (when-not result-error
                        (swap! local-db assoc
                               :selected-index nil
                               :suggestions suggestions
                               :autocomplete-visible? (seq suggestions)))
                      (recur)))))

        ;; Add click listener for outside clicks
        (let [listener (fn [e]
                         (when-let [dropdown-element (js/document.querySelector "#header-input .autocomplete-dropdown")]
                           (let [click-inside? (.contains dropdown-element (.-target e))]
                             (when-not click-inside?
                               (swap! local-db assoc :autocomplete-visible? false)))))]
          (reset! click-listener listener)
          (js/document.addEventListener "click" listener)))

      :component-will-unmount
      (fn [_this]
        ;; Clean up channels
        (when-let [raw-input$ (:raw-input$ @local-db)]
          (a/close! raw-input$))
        (when @autocomplete-input$
          (a/close! @autocomplete-input$))
        (when @autocomplete-output$
          (a/close! @autocomplete-output$))

        ;; Clean up click listener
        (when @click-listener
          (js/document.removeEventListener "click" @click-listener))

        ;; Clean up track subscription
        (when @track-input-changed
          (r/dispose! @track-input-changed)))

      :reagent-render
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
            [:div.nav-input-container
             [nav-input db local-db]
             [autocomplete-dropdown db local-db]]
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
              [:span {:class [:material-symbols-sharp :clickable]} "λ"]]]]]))})))
