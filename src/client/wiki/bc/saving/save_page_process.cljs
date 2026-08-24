(ns wiki.bc.saving.save-page-process
  (:require [wiki.bc.http :as http]
            [wiki.bc.request-process :as request-process]))

(defn- start-save-page-request [{:keys [page-name data]}]
  (http/http-post* "/api/save" (pr-str {:page page-name
                                        :data data})))

(defn <create-save-page-process
  ([saving-page$]
   (<create-save-page-process saving-page$ {}))
  ([saving-page$ {:keys [start-request] :or {start-request start-save-page-request}}]
   (request-process/create-request-process saving-page$ start-request)))
