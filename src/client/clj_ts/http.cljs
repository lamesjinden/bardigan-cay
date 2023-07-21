(ns clj-ts.http
  (:require [cljs.core.async :as a])
  (:import [goog.net XhrIo]))

(goog-define env "production")
(goog-define env-port "")

(defn- <http-send [{:keys [url method body headers timeout with-credentials?]
                    :or   {body              nil
                           headers           nil
                           timeout           0
                           with-credentials? false}}]
  (when (not url)
    (throw (js/error "url was not defined")))
  (when (not method)
    (throw (js/error "method was not defined")))

  (let [hostname (.-hostname js/location)
        url (if (= env "dev")
              (str "//" hostname ":" env-port url)
              url)
        chan (a/promise-chan)
        callback (fn [e]
                   (let [response {:status     (-> e (.-target) (.getStatus))
                                   :statusText (-> e (.-target) (.getStatusText))
                                   :headers    (-> e (.-target) (.getResponseHeaders))
                                   :body       (-> e (.-target) (.getResponseText))}]
                     (when (.isSuccess (.-target e))
                       (a/put! chan response))))]
    (.send XhrIo
           url
           callback
           method
           body
           (clj->js headers)
           timeout
           with-credentials?)
    chan))

(defn <http-get [url & {:keys [headers timeout with-credentials?]}]
  (<http-send {:url               url
               :method            "GET"
               :headers           headers
               :timeout           timeout
               :with-credentials? with-credentials?}))

(defn <http-post [url body & {:keys [headers timeout with-credentials?]}]
  (<http-send {:url               url
               :method            "POST"
               :body              body
               :headers           headers
               :timeout           timeout
               :with-credentials? with-credentials?}))