(ns wiki.bc.saving.save-card-process
  (:require [wiki.bc.http :as http]
            [wiki.bc.request-process :as request-process]))

(defn- start-save-card-request [{:keys [page-name hash data]}]
  (http/http-post* "/api/replacecard" (pr-str {:page page-name
                                               :data data
                                               :hash hash})))

(defn <create-save-card-process
  ([saving-card$]
   (<create-save-card-process saving-card$ {}))
  ([saving-card$ {:keys [start-request] :or {start-request start-save-card-request}}]
   (request-process/create-request-process saving-card$ start-request)))
