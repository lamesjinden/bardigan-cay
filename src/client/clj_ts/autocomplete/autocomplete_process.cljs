(ns clj-ts.autocomplete.autocomplete-process
  (:require [cljs.core.async :as a]
            [clojure.string :as str]
            [clj-ts.http :as http]))

;; region transducers

(defn query-length-filter-transducer
  "Transducer that filters out queries shorter than min-length"
  [min-length]
  (filter (fn [query]
            (let [trimmed-query (str/trim (or query ""))]
              (>= (count trimmed-query) min-length)))))

(defn create-debounced-channel
  "Creates a debounced channel that only emits values after delay-ms of inactivity
   Returns a channel that should be used as the target for puts
   
   related: https://stackoverflow.com/questions/35663415/throttle-functions-with-core-async
   "

  [source-chan delay-ms]
  (let [debounced-chan (a/chan)
        timeout-chan (a/chan)]
    (a/go-loop [timeout-id nil]
      (let [[value port] (a/alts! [source-chan timeout-chan])]
        (cond
          (= port timeout-chan)
          ;; Timeout fired - emit the pending value
          (do
            (a/>! debounced-chan value)
            (recur nil))

          (= port source-chan)
          (if (nil? value)
            ;; Source channel closed - cleanup everything
            (do
              (when timeout-id (js/clearTimeout timeout-id))
              (a/close! timeout-chan)
              (a/close! debounced-chan))
            ;; New value - reset timeout
            (do
              (when timeout-id (js/clearTimeout timeout-id))
              (let [new-timeout-id (js/setTimeout #(a/put! timeout-chan value) delay-ms)]
                (recur new-timeout-id)))))))
    debounced-chan))

(defn distinct-until-changed-transducer
  "Stateful transducer that only emits values when they differ from the previous value
   Optionally takes a key-fn to extract comparison value and compare-fn for custom equality
   
   Examples:
   (distinct-until-changed-transducer) ; uses = for comparison
   (distinct-until-changed-transducer identity) ; uses = on identity
   (distinct-until-changed-transducer str/lower-case) ; case-insensitive comparison
   (distinct-until-changed-transducer identity not=) ; custom comparison function"
  ([] (distinct-until-changed-transducer identity =))
  ([key-fn] (distinct-until-changed-transducer key-fn =))
  ([key-fn compare-fn]
   (fn [rf]
     (let [prev-value (volatile! ::none)]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input]
          (let [current-key (key-fn input)
                prev-key @prev-value]
            (if (or (= prev-key ::none)
                    (not (compare-fn current-key prev-key)))
              (do
                (vreset! prev-value current-key)
                (rf result input))
              result))))))))

;; endregion

;; region autocomplete process

(defn <create-autocomplete-process
  "Creates a reusable autocomplete process
   Parameters:
   - input$ - channel with pre-applied transducers (filtering, debouncing, etc.)
   
   Returns a channel that emits autocomplete results in the format:
   {:query string :suggestions [...] :result-error error-or-nil}"
  [input$]
  (let [result$ (a/chan)]
    ;; Start the autocomplete process loop
    (a/go-loop []
      (when-some [query (a/<! input$)]
        (let [trimmed-query (str/trim query)]
          (try
            (when-let [response (a/<! (http/<http-get (str "/api/search/autocomplete?q=" (js/encodeURI trimmed-query))))]
              (let [{body-text :body} response
                    suggestions (js->clj (js/JSON.parse body-text) :keywordize-keys true)]
                (a/put! result$ {:query query :suggestions suggestions})))
            (catch js/Error e
              (js/console.error "Autocomplete fetch failed:" e)
              (a/put! result$ {:query query :suggestions [] :result-error e}))))
        (recur)))

    ;; Return the result channel
    result$))

;; endregion