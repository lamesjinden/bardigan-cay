(ns wiki.bc.searching.search-process
  (:require [wiki.bc.http :as http]
            [wiki.bc.request-process :as request-process]))

(defn- start-search-request [{:keys [query]}]
  (http/http-get* (str "/api/search?q=" query)))

(defn <create-search-process
  ([searching$]
   (<create-search-process searching$ {}))
  ([searching$ {:keys [start-request] :or {start-request start-search-request}}]
   (request-process/create-request-process searching$ start-request)))
