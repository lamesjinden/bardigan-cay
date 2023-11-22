(ns clj-ts.app
  (:require [clojure.edn :as edn]
            [clojure.pprint]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [taoensso.timbre :as timbre :refer [debug fatal]]
            [clj-ts.server :as server])
  (:gen-class))

;; region defaults

(def default-log-level :info)

(def default-options
  {:port       4545
   :ip         "127.0.0.1"
   :directory  "./bedrock/"
   :name       "Yet Another CardiganBay Wiki"
   :site       "/"
   :init       "HelloWorld"
   :links      "./"
   :extension  ".html"
   :export-dir "./bedrock/exported/"
   :config     "./bedrock/system/config.edn"
   :nav-links  ["HelloWorld" "InQueue" "Transcript" "RecentChanges" "Help"]})

;; endregion

;; region cli

(def cli-options
  [["-h" "--help"]
   ["-v" nil "Verbosity level" :id :verbosity :default 0 :update-fn inc]
   ["-i" "--ip IP" "IP Address"]
   ["-p" "--port PORT" "Port number"
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-d" "--directory DIR" "Pages directory"]
   ["-f" "--config CONFIG_PATH" "Path to configuration parameters file"]
   [nil "--name NAME" "Wiki Name"]
   [nil "--site SITE" "Site URL "]
   [nil "--init INIT" "Start Page"]
   [nil "--navlinks NAVLINKS" "Navigation Header Links"
    :id :nav-links
    :parse-fn (fn [arg] (mapv str/trim (str/split arg #",")))]
   [nil "--links LINK" "Export Links"]
   [nil "--extension EXPORTED_EXTENSION" "Exported Extension"]
   [nil "--export-dir DIR" "Export Directory"]])

(defn args->opts [args]
  (cli/parse-opts args cli-options))

(defn print-header []
  (println (str "\n" "Welcome to Cardigan Bay\n")))

(defn print-summary [summary]
  (println "\nCLI Options")
  (println summary))

(defn print-errors [errors]
  (binding [*out* *err*]
    (when (seq errors)
      (println))
    (doseq [e errors]
      (println e e))))

;; endregion

;; region settings

(defn- read-config-file [config-file-path]
  (try
    (-> config-file-path
        slurp
        edn/read-string)
    (catch Exception _
      {})))

(defn gather-application-settings [options]
  (let [config-file-settings (or (some-> (:config options)
                                         (read-config-file))
                                 {})]
    (merge default-options config-file-settings options)))

;; endregion

;; region logging

(defn configure-logging [application-settings]
  (let [verbosity (:verbosity application-settings)
        min-level (cond
                    (>= verbosity 3) :trace
                    (= verbosity 2) :debug
                    (= verbosity 1) :info
                    :else default-log-level)]
    (timbre/set-min-level! min-level)))

(defn log-application-settings [application-settings]
  (debug "Application Settings:" "\n" application-settings))

;; endregion

;; region lifetime

(def dispose-server! (atom nil))

(defn- stop-server []
  (when-let [disposable @dispose-server!]
    (debug "Stopping server...")

    (disposable :timeout 100)

    (debug "Server stopped.")
    (reset! dispose-server! nil))
  (shutdown-agents))

(defn- start-server [application-settings]
  (configure-logging application-settings)

  (let [card-server-ref (server/create-card-server application-settings)
        app (server/create-request-pipeline card-server-ref)
        disposable (server/create-server application-settings app)]
    (reset! dispose-server! disposable))

  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable #'stop-server)))

;; endregion

;; region entry point

(defn -main [& args]
  (print-header)
  (let [{:keys [options errors summary]} (args->opts args)
        application-settings (gather-application-settings options)]
    (configure-logging application-settings)
    (log-application-settings application-settings)
    (cond
      (:help options)
      (print-summary summary)

      errors
      (do
        (print-errors errors)
        (System/exit 1))

      :else
      (try
        (start-server application-settings)
        (catch Exception e
          (fatal e)
          (System/exit 2))))))

;; endregion