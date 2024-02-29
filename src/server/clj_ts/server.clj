(ns clj-ts.server
  (:require [org.httpkit.server :refer [run-server]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.middleware.json :refer [wrap-json-body]]
            [selmer.parser]
            [selmer.util]
            [taoensso.timbre :refer [debug]]
            [clj-ts.card-server :as card-server]
            [clj-ts.export.static-export :as export]
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
        "==PageExporter Report==\n"
        "\n"
        (-> card-server-state :page-exporter .report)
        "\n"
        "\n"
        "-----------------------------------------------------------------------------------------------"
        "\n")))

(defn create-card-server
  "initializes server state contained within an Atom and returns it"
  [application-settings]
  (let [{:keys [directory export-dir extension links name site port init nav-links]} application-settings
        page-store (pagestore/make-page-store directory export-dir)
        page-exporter (export/make-page-exporter page-store extension links)
        card-server-ref (card-server/create-card-server name site port init nav-links nil page-store page-exporter)
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

(defn create-request-pipeline
  "returns the ring request-handling pipeline"
  [^Atom card-server-ref]
  (let [ring-defaults (-> api-defaults
                          (assoc :static {:resources "public"}))]
    (-> #'routing/request-handler
        (wrap-card-server card-server-ref)
        (wrap-json-body {:keywords? true})
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
