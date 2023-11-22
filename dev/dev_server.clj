(ns dev-server
  (:require
    [clojure.pprint]
    [org.httpkit.server :as http]
    [ring.middleware.reload :as reload]
    [ring.middleware.cors :as cors]
    [clj-ts.server :as server]
    [clj-ts.app :as app]))

(set! *warn-on-reflection* true)

(defonce server (atom nil))

(defn stop-server []
  (when-not (nil? @server)
    (println "stopping server")
    (println)

    (@server :timeout 100)
    (reset! server nil)))

(defn create-server [& args]
  (println)
  (println "creating dev server:")

  (let [{:keys [options]} (app/args->opts args)
        application-settings (app/gather-application-settings options)]
    (println)
    (println "application-settings:")
    (clojure.pprint/pprint application-settings)
    (println)
    (println "initialize dev server app:")
    (println)

    (let [request-pipeline (-> application-settings
                               (server/create-card-server)
                               (server/create-request-pipeline)
                               (reload/wrap-reload)
                               (cors/wrap-cors :access-control-allow-origin [#".*"]
                                               :access-control-allow-methods [:get :put :post :delete]))]
      (reset! server (http/run-server
                       request-pipeline
                       (server/gather-server-settings application-settings))))))

(defn -main [& args]
  (apply create-server args))

(comment

  (create-server
    "--directory" "../../Documents/wiki/bedrock/"
    "--export-dir" "../../Documents/wiki/bedrock/exported/"
    "-v")

  (stop-server)

  ;
  )

