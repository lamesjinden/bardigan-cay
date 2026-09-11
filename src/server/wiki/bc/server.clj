(ns wiki.bc.server
  (:require [clojure.core.async :as a]
            [org.httpkit.server :refer [run-server]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [selmer.parser]
            [selmer.util]
            [taoensso.timbre :refer [debug]]
            [job-server.middleware.server :as job-server]
            [wiki.bc.card-server :as card-server]
            [wiki.bc.jobs :as jobs]
            [wiki.bc.routing :as routing]
            [wiki.bc.storage.index :as index]
            [wiki.bc.storage.page-store :as pagestore])
  (:import (clojure.lang Atom)))

(defn- print-card-server-state [card-server-state]
  (debug
   (str "\n"
        "Wiki Name:\t" (:wiki-name card-server-state) "\n"
        "Site URL:\t" (:site-url card-server-state) "\n"
        "Start Page:\t" (:start-page card-server-state) "\n"
        "Nav Links:\t" (:nav-links card-server-state) "\n"
        "Port No:\t" (:port-no card-server-state) "\n"
        "\n"
        "==PageStore Report==\n"
        "\n"
        (-> card-server-state :page-store .report)
        "\n"
        "-----------------------------------------------------------------------------------------------"
        "\n")))

;; tracks the live index so that dev-server re-creation and process
;; shutdown can free its LMDB env and scratch directory
(defonce ^:private page-index* (atom nil))

(defn- install-page-index!
  "Installs a fully built index as the live one, then closes whatever it
  replaced -- in that order, so a failed build never destroys a working
  index and dev reloads stay leak-free."
  [page-index]
  (when-let [previous (first (reset-vals! page-index* page-index))]
    (index/close! previous))

  page-index)

(defn close-page-index!
  "Closes the live index and removes its scratch directory; the process
  shutdown path calls this so no LMDB copy of the wiki outlives the app."
  []
  (when-let [current (first (reset-vals! page-index* nil))]
    (index/close! current)))

(defn- build-page-index
  "Opens and fully builds an index over page-store, closing the fresh
  scratch directory again if the build fails partway."
  [page-store]
  (let [page-index (index/open-index)]
    (try
      (index/build! page-index page-store)
      (catch Throwable t
        (index/close! page-index)
        (throw t)))))

(defn create-card-server
  "initializes server state contained within an Atom and returns it"
  [application-settings]
  (let [{:keys [directory name site port init nav-links]} application-settings
        page-store (pagestore/make-page-store directory)
        page-index (install-page-index! (build-page-index page-store))
        card-server-ref (card-server/create-card-server name site port init nav-links page-index page-store)
        card-server-state @card-server-ref]
    (print-card-server-state card-server-state)
    card-server-ref))

(defn- print-server-settings [server-settings]
  (debug
   (str "\n"
        (when (:ip server-settings) (str "IP:\t" (:ip server-settings) "\n"))
        (when (:port server-settings) (str "Port:\t" (:port server-settings) "\n"))
        (when (:thread server-settings) (str "Threads:\t" (:thread server-settings) "\n"))
        (when (:worker-name-prefix server-settings) (str "Worker Prefix:\t" (:worker-name-prefix server-settings) "\n"))
        (when (:queue-size server-settings) (str "Queue Size:\t" (:queue-size server-settings) "\n"))
        (when (:max-body server-settings) (str "Max Body Size (bytes):\t" (:max-body server-settings) "\n"))
        (when (:max-line server-settings) (str "Max Line Length:\t" (:max-line server-settings) "\n")))))

(defn wrap-card-server [handler card-server-ref]
  (fn [request]
    (let [request (assoc request :card-server card-server-ref)]
      (handler request))))

;; closing the previous stop-chan on pipeline (re)creation shuts down the
;; prior job-server processes -- keeps dev-server reloads leak-free
(defonce ^:private job-server-stop-chan* (atom nil))

(defn- next-job-server-stop-chan! []
  (let [stop-chan (a/chan)]
    (when-let [previous (first (reset-vals! job-server-stop-chan* stop-chan))]
      (a/close! previous))

    stop-chan))

(defn- wrap-stringified-params
  "api-defaults keywordizes params but job-server reads string keys; adds
  string-keyed copies alongside so both styles resolve."
  [handler]
  (fn [request]
    (handler (update request :params
                     (fn [params]
                       (merge params (update-keys params name)))))))

(defn create-request-pipeline
  "returns the ring request-handling pipeline"
  [^Atom card-server-ref]
  (let [ring-defaults (-> api-defaults
                          (assoc :static {:resources "public"}))
        job-mapping (jobs/create-job-mapping card-server-ref)]
    (-> #'routing/request-handler
        (wrap-card-server card-server-ref)
        (wrap-json-body {:keywords? true})
        ;; :max-history matches artifacts/max-artifacts so every successful job
        ;; in the list still has a live download; older records are evicted.
        (job-server/wrap-job-server job-mapping "/api" (next-job-server-stop-chan!) {:max-history 10})
        (wrap-stringified-params)
        ;; serializes the job-server's clojure-map response bodies;
        ;; string/File bodies from BC's own handlers pass through untouched
        (wrap-json-response)
        (wrap-defaults ring-defaults))))

(defn gather-server-settings [application-settings]
  (let [server-settings (-> application-settings
                            (select-keys [:ip :port :thread :worker-name-prefix :queue-size :max-body :max-line])
                            ;; LMDB write txns have OS-thread affinity; on JDK 24+ a virtual
                            ;; thread can migrate carriers mid-txn and corrupt the index, so
                            ;; keep http-kit workers on platform threads (2.8+ defaults to
                            ;; virtual threads on JVM 21+, ignoring :thread).
                            (assoc :allow-virtual? false))]
    (print-server-settings server-settings)
    server-settings))

(defn create-server [application-settings request-pipeline]
  (let [server-settings (gather-server-settings application-settings)]
    (debug "Running server...")
    (let [disposable (run-server request-pipeline server-settings)]
      (debug "Server running.")
      disposable)))

;; endregion
