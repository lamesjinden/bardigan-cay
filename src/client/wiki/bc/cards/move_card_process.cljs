(ns wiki.bc.cards.move-card-process
  (:require [wiki.bc.http :as http]
            [wiki.bc.request-process :as request-process]))

(defn- start-move-card-request [{:keys [from-page to-page hash]}]
  (http/http-post* "/api/movecard" (pr-str {:from from-page
                                            :to   to-page
                                            :hash hash})))

(defn <create-move-card-process
  ([moving-card$]
   (<create-move-card-process moving-card$ {}))
  ([moving-card$ {:keys [start-request] :or {start-request start-move-card-request}}]
   (request-process/create-request-process moving-card$ start-request)))
