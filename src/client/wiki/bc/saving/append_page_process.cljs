(ns wiki.bc.saving.append-page-process
  (:require [wiki.bc.http :as http]
            [wiki.bc.request-process :as request-process]))

(defn- start-append-page-request [{:keys [page-name data]}]
  (http/http-post* "/api/append" (pr-str {:page page-name
                                          :data data})))

(defn <create-append-page-process
  ([appending-page$]
   (<create-append-page-process appending-page$ {}))
  ([appending-page$ {:keys [start-request] :or {start-request start-append-page-request}}]
   (request-process/create-request-process appending-page$ start-request)))
