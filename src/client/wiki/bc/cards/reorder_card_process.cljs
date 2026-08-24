(ns wiki.bc.cards.reorder-card-process
  (:require [wiki.bc.http :as http]
            [wiki.bc.request-process :as request-process]))

(defn- start-reorder-card-request [{:keys [page-name hash direction]}]
  (http/http-post* "/api/reordercard" (pr-str {:page      page-name
                                               :hash      hash
                                               :direction direction})))

(defn <create-reorder-card-process
  ([reordering-card$]
   (<create-reorder-card-process reordering-card$ {}))
  ([reordering-card$ {:keys [start-request] :or {start-request start-reorder-card-request}}]
   (request-process/create-request-process reordering-card$ start-request)))
