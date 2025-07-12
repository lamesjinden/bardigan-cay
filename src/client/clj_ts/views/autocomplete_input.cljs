(ns clj-ts.views.autocomplete-input
  (:require [cljs.core.async :as a]
            [clojure.string :as str]
            [reagent.core :as r]
            [clj-ts.keyboard :as keyboard]
            [clj-ts.views.autocomplete-dropdown :refer [autocomplete-dropdown]]
            [clj-ts.autocomplete.autocomplete-process :as autocomplete]))

;; region keyboard handlers

(defn- autocomplete-input-on-key-enter [db local-db e on-submit]
  (let [input-value (-> e .-target .-value str/trim)]
    (when (seq input-value)
      (on-submit db local-db e input-value))))

(defn- autocomplete-input-on-escape [_db local-db _e]
  (if (:autocomplete-visible? @local-db)
    (swap! local-db assoc :autocomplete-visible? false)
    (swap! local-db assoc :input-value nil)))

(defn- focus-autocomplete-element [_db local-db]
  (let [autocomplete-visible? (:autocomplete-visible? @local-db)
        autocomplete-element (:autocomplete-element @local-db)]
    (when (and autocomplete-visible? autocomplete-element)
      (.focus autocomplete-element)
      (swap! local-db assoc :selected-index 0))))

(defn- autocomplete-input-on-key-up [db local-db e on-submit]
  (let [key-code (.-keyCode e)]
    (condp = key-code
      keyboard/key-enter-code (autocomplete-input-on-key-enter db local-db e on-submit)
      keyboard/key-escape-code (autocomplete-input-on-escape db local-db e)
      keyboard/key-down-code (focus-autocomplete-element db local-db)
      nil)))

;; endregion

;; region autocomplete lifecycle

(defn- initialize-autocomplete-system! [local-db min-query-length debounce-ms]
  (let [raw-input$ (a/chan 1 (comp
                              (map (fn [term] (str/trim term)))
                              (filter (fn [term] (not (str/blank? term))))
                              (autocomplete/distinct-until-changed-transducer)
                              (autocomplete/query-length-filter-transducer min-query-length)))
        debounced$ (autocomplete/create-debounced-channel raw-input$ debounce-ms)
        output$ (autocomplete/<create-autocomplete-process debounced$)]

    ;; Store channels for cleanup
    (swap! local-db assoc :raw-input$ raw-input$)

    ;; Return channels for external management
    {:debounced$ debounced$
     :output$ output$}))

(defn- setup-input-tracking! [local-db track-input-changed]
  (reset! track-input-changed
          (r/track! (fn []
                      (when-let [value (:input-value @local-db)]
                        (when-let [raw-input$ (:raw-input$ @local-db)]
                          (a/put! raw-input$ value)))))))

(defn- setup-results-processing! [local-db output$ results-process]
  (reset! results-process
          (a/go-loop []
            (when-some [result (a/<! output$)]
              (let [{:keys [_query suggestions result-error]} result]
                (when-not result-error
                  (swap! local-db assoc
                         :selected-index nil
                         :suggestions suggestions
                         :autocomplete-visible? (seq suggestions)))
                (recur))))))

(defn- setup-click-outside-listener! [local-db click-listener container-selector]
  (let [listener (fn [e]
                   (when-let [dropdown-element (:autocomplete-element @local-db)]
                     (let [container-element (js/document.querySelector container-selector)
                           click-in-container? (and container-element (.contains container-element (.-target e)))
                           click-in-dropdown? (.contains dropdown-element (.-target e))]
                       (when-not (or click-in-container? click-in-dropdown?)
                         (swap! local-db assoc :autocomplete-visible? false)))))]
    (reset! click-listener listener)
    (js/document.addEventListener "click" listener)))

(defn- cleanup-autocomplete-system! [local-db autocomplete-input$ autocomplete-output$
                                     click-listener track-input-changed]
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

;; endregion

;; region main component

(defn autocomplete-input
  "Reusable autocomplete input component.
   
   Required props:
   - :db - App database atom
   - :local-db - Local component state atom (must contain :input-value, :suggestions, :autocomplete-visible?)
   - :placeholder - Input placeholder text
   - :on-submit - Function called when input is submitted (db local-db e input-value)
   - :on-clicked - Function called when dropdown item is clicked (db local-db e name)  
   - :on-key-up-enter - Function called when enter is pressed on dropdown item (db local-db e name)
   
   Optional props:
   - :class-name - CSS class for the input element (default: 'autocomplete-input')
   - :container-class - CSS class for the container div (default: 'autocomplete-input-container')
   - :container-selector - CSS selector for click-outside detection (default: uses container-class)
   - :min-query-length - Minimum characters before search triggers (default: 3)
   - :debounce-ms - Debounce delay in milliseconds (default: 300)"

  [{:keys [db local-db placeholder on-submit on-clicked on-key-up-enter
           class-name container-class container-selector
           min-query-length debounce-ms]
    :or {class-name "autocomplete-input"
         container-class "autocomplete-input-container"
         min-query-length 3
         debounce-ms 300}}]

  (let [autocomplete-input$ (atom nil)
        autocomplete-output$ (atom nil)
        click-listener (atom nil)
        track-input-changed (atom nil)
        results-process (atom nil)
        selector (or container-selector (str "." container-class))]

    (r/create-class
     {:component-did-mount
      (fn [_this]
        ;; Initialize autocomplete process
        (let [{:keys [debounced$ output$]} (initialize-autocomplete-system! local-db min-query-length debounce-ms)]
          (reset! autocomplete-input$ debounced$)
          (reset! autocomplete-output$ output$))

        ;; Set up input tracking
        (setup-input-tracking! local-db track-input-changed)

        ;; Start results processing loop
        (setup-results-processing! local-db @autocomplete-output$ results-process)

        ;; Add click listener for outside clicks
        (setup-click-outside-listener! local-db click-listener selector))

      :component-will-unmount
      (fn [_this]
        (cleanup-autocomplete-system! local-db autocomplete-input$ autocomplete-output$
                                      click-listener track-input-changed))

      :reagent-render
      (fn [{:keys [db local-db placeholder on-submit on-clicked on-key-up-enter
                   class-name container-class]
            :or {class-name "autocomplete-input"
                 container-class "autocomplete-input-container"}}]
        [:div {:class [container-class]}
         [:input {:type        "text"
                  :class       class-name
                  :value       (:input-value @local-db)
                  :on-change   #(swap! local-db assoc :input-value (-> % .-target .-value))
                  :on-key-up   #(autocomplete-input-on-key-up db local-db % on-submit)
                  :placeholder placeholder}]
         [autocomplete-dropdown db local-db on-clicked on-key-up-enter]])})))

;; endregion