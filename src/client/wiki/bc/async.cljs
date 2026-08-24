(ns wiki.bc.async
  "Channel->channel operators, the core.async analog of Rx operators.

   Operators that only transform values synchronously belong in transducers;
   the operators here need what transducers cannot express - time (debounce)
   or racing a second event source (switch)."
  (:require [cljs.core.async :as a]))

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

(defn create-switching-channel
  "switchMap as a channel operator: starts a request per source value and
   emits {:input value :response response} for each response, except that a
   newer source value supersedes an in-flight request - its request is
   aborted and its response is never emitted (latest wins).

   start-request: value -> {:response$ <promise-chan> :abort! <fn>}
   (the wiki.bc.http/http-get* contract).

   on-supersede! (optional): called with the superseded value before its
   request is aborted, e.g. to resolve a per-value out-chan.

   Closing the source aborts any in-flight request and closes the returned
   channel."
  [source$ start-request & {:keys [on-supersede!]}]
  (let [out$ (a/chan)]
    (a/go-loop [in-flight nil]
      (let [ports (cond-> [source$]
                    (some? in-flight) (conj (:response$ in-flight)))
            [value channel] (a/alts! ports :priority true)]
        (condp = channel
          source$
          (do
            (when in-flight
              ;; resolve the superseded consumer first, then abort; aborting
              ;; fires the request callback synchronously - and before the
              ;; next request begins, so progress events stay ordered even
              ;; when both requests share a progress id (the url)
              (when on-supersede!
                (on-supersede! (:input in-flight)))
              ((:abort! in-flight)))
            (if (nil? value)
              (a/close! out$)
              (recur (-> (start-request value)
                         (assoc :input value)))))

          ;; else: the in-flight request's response delivered
          (do
            ;; an aborted response only arrives here when abort! raced the
            ;; response into the alts! set - it was superseded, drop it
            (when-not (:aborted? value)
              (a/put! out$ {:input    (:input in-flight)
                            :response value}))
            (recur nil)))))
    out$))
