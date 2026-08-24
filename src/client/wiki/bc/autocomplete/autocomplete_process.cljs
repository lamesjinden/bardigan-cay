(ns wiki.bc.autocomplete.autocomplete-process
  (:require [cljs.core.async :as a]
            [clojure.string :as str]
            [wiki.bc.async :as async]
            [wiki.bc.http :as http]))

;; region transducers

(defn query-length-filter-transducer
  "Transducer that filters out queries shorter than min-length"
  [min-length]
  (filter (fn [query]
            (let [trimmed-query (str/trim (or query ""))]
              (>= (count trimmed-query) min-length)))))

(defn sexp-filter-transducer
  "Transducer that filters out queries that start with an opening parenthesis
   Useful for ignoring incomplete S-expressions"
  []
  (filter (fn [query]
            (let [trimmed-query (str/trim (or query ""))]
              (not (str/starts-with? trimmed-query "("))))))

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

(defn- get-suggestions [query]
  (http/http-get* (str "/api/search/autocomplete?q=" (js/encodeURI (str/trim query)))))

(defn- suggestions-result [{:keys [input response]}]
  (try
    {:query       input
     :suggestions (js->clj (js/JSON.parse (:body response)) :keywordize-keys true)}
    (catch js/Error e
      (js/console.error "Autocomplete fetch failed:" e)
      {:query input :suggestions [] :result-error e})))

(defn <create-autocomplete-process
  "Creates a reusable autocomplete process: queries taken from input$ are
   fetched with switchMap semantics - a newer query aborts the in-flight
   request, so only the latest query's suggestions are emitted. Closing
   input$ aborts any in-flight request and closes the returned channel.

   Parameters:
   - input$ - channel with pre-applied transducers (filtering, debouncing, etc.)
   - opts (optional) - {:fetch-suggestions (fn [query] {:response$ .. :abort! ..})}

   Returns a channel that emits autocomplete results in the format:
   {:query string :suggestions [...] :result-error error-or-nil}"
  ([input$]
   (<create-autocomplete-process input$ {}))
  ([input$ {:keys [fetch-suggestions] :or {fetch-suggestions get-suggestions}}]
   (let [result$ (a/chan 1 (map suggestions-result))]
     (a/pipe (async/create-switching-channel input$ fetch-suggestions) result$)
     result$)))

;; endregion
