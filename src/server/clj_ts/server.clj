(ns clj-ts.server
  (:require [clojure.core.async :as a]
            [org.httpkit.server :refer [run-server]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [selmer.parser]
            [selmer.util]
            [taoensso.timbre :refer [debug]]
            [job-server.middleware.server :as job-server]
            [clj-ts.card-server :as card-server]
            [clj-ts.jobs :as jobs]
            [clj-ts.routing :as routing]
            [clj-ts.storage.page-store :as pagestore])
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

(defn create-card-server
  "initializes server state contained within an Atom and returns it"
  [application-settings]
  (let [{:keys [directory name site port init nav-links]} application-settings
        page-store (pagestore/make-page-store directory)
        card-server-ref (card-server/create-card-server name site port init nav-links nil page-store)
        card-server-state @card-server-ref]
    (print-card-server-state card-server-state)
    (card-server/regenerate-db! card-server-ref)
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
        (job-server/wrap-job-server job-mapping "/api" (next-job-server-stop-chan!))
        (wrap-stringified-params)
        ;; serializes the job-server's clojure-map response bodies;
        ;; string/File bodies from BC's own handlers pass through untouched
        (wrap-json-response)
        (wrap-defaults ring-defaults))))

(defn gather-server-settings [application-settings]
  (let [server-settings (select-keys application-settings [:ip :port :thread :worker-name-prefix :queue-size :max-body :max-line])]
    (print-server-settings server-settings)
    server-settings))

(defn create-server [application-settings request-pipeline]
  (let [server-settings (gather-server-settings application-settings)]
    (debug "Running server...")
    (let [disposable (run-server request-pipeline server-settings)]
      (debug "Server running.")
      disposable)))

;; endregion
