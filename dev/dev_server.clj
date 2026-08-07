(ns dev-server
  (:require
   [clojure.pprint]
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
  (println "== creating dev server ==")

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
      (reset! server (server/create-server application-settings request-pipeline)))))

(defn -main [& args]
  (let [{:keys [options errors summary]} (app/args->opts args)]
    (cond
      (:help options)
      (app/print-summary summary)

      errors
      (do
        (app/print-errors errors)
        (System/exit 1))

      :else
      (apply create-server args))))

(comment

  (create-server
   "--directory" "../../Documents/wiki/bedrock/"
   "--ip=0.0.0.0"
   "-v")

  (stop-server)

  ;
  )