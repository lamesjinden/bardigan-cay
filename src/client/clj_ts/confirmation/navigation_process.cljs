(ns clj-ts.confirmation.navigation-process
  (:require [cljs.core.async :as a]
            [clj-ts.events.confirmation :as e-confirm]
            [clj-ts.http :as http]))

(def ^:private page-request-timeout-ms 30000)

(defn- update-edit-sessions [editing {:keys [id action]}]
  (if (= action :start)
    (conj editing id)
    (disj editing id)))

(defn- get-page [page-name]
  (http/http-get* (str "/api/page/" (js/encodeURI page-name))
                  :timeout page-request-timeout-ms))

(defn- begin-fetch [fetch-page page-name out-chan]
  (-> (fetch-page page-name)
      (assoc :out-chan out-chan)))

(defn- cancel! [{:keys [out-chan abort!]}]
  ;; resolve the consumer first (out-chan is a promise-chan: first put wins),
  ;; then abort; aborting fires the request callback synchronously and is a
  ;; no-op if the request already completed
  (a/put! out-chan :canceled)
  (abort!))

(defn- deliver-result! [{:keys [out-chan]} {:keys [isSuccess] :as result}]
  (if isSuccess
    (a/put! out-chan result)
    (a/close! out-chan)))

(defn <create-nav-process
  ([navigating$ editing$]
   (<create-nav-process navigating$ editing$ {:fetch-page get-page
                                              :<confirm   e-confirm/<notify-confirm}))
  ([navigating$ editing$ {:keys [fetch-page <confirm]}]
   (a/go-loop [editing #{}
               in-flight nil]
     (let [ports (cond-> [editing$ navigating$]
                   (some? in-flight) (conj (:response$ in-flight)))
           [value channel] (a/alts! ports :priority true)]
       (condp = channel
         editing$
         (recur (update-edit-sessions editing value) in-flight)

         navigating$
         (let [{:keys [page-name out-chan]} value]
           ;; latest wins: a newer navigation supersedes any in-flight request,
           ;; even if the newer navigation is later declined in the confirm dialog
           (when in-flight
             (cancel! in-flight))
           (if (empty? editing)
             (recur editing (begin-fetch fetch-page page-name out-chan))
             (let [response (a/<! (<confirm))]
               (if (= response :ok)
                 (recur #{} (begin-fetch fetch-page page-name out-chan))
                 (do
                   ;; declined: resolve the requester instead of leaking a parked chain
                   (a/put! out-chan :canceled)
                   (recur editing nil))))))

         ;; else: the in-flight request's response delivered
         (do
           (deliver-result! in-flight value)
           (recur editing nil)))))))
