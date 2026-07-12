(ns clj-ts.http
  (:require [cljs.core.async :as a]
            [clj-ts.events.progression :as e-progress])
  (:import [goog.net ErrorCode XhrIo]))

(defn- aborted? [^js target]
  (= (.getLastErrorCode target) (.-ABORT ErrorCode)))

(defn- http-send [{:keys [url method body headers timeout with-credentials?]
                   :or   {body              nil
                          headers           nil
                          timeout           0
                          with-credentials? false}}]
  (when (not url)
    (throw (js/Error. "url was not defined")))
  (when (not method)
    (throw (js/Error. "method was not defined")))

  (let [progress-id url
        response$ (a/promise-chan)
        callback (fn [^js e]
                   (let [target (.-target e)]
                     (cond
                       (.isSuccess target)
                       (do
                         (e-progress/notify-progress-end progress-id)
                         (a/put! response$ {:isSuccess  true
                                            :status     (.getStatus target)
                                            :statusText (.getStatusText target)
                                            :headers    (.getResponseHeaders target)
                                            :body       (.getResponseText target)}))

                       ;; a deliberate abort is a cancellation, not a failure —
                       ;; reporting :fail would flag the progress bar red for a
                       ;; request the app chose to discard
                       (aborted? target)
                       (do
                         (e-progress/notify-progress-end progress-id)
                         (a/put! response$ {:isSuccess  false
                                            :aborted?   true
                                            :status     (.getStatus target)
                                            :statusText (.getStatusText target)}))

                       :else
                       (do
                         (e-progress/notify-progress-fail progress-id)
                         (a/put! response$ {:isSuccess  false
                                            :status     (.getStatus target)
                                            :statusText (.getStatusText target)})))))]
    (e-progress/notify-progress-begin progress-id)
    (let [xhr (.send XhrIo
                     url
                     callback
                     method
                     body
                     (clj->js headers)
                     timeout
                     with-credentials?)]
      {:response$ response$
       :abort!    (fn [] (.abort ^js xhr))})))

(defn <http-get [url & {:keys [headers timeout with-credentials?]}]
  (:response$ (http-send {:url               url
                          :method            "GET"
                          :headers           headers
                          :timeout           timeout
                          :with-credentials? with-credentials?})))

(defn http-get*
  "Raw variant of <http-get: returns {:response$ <promise-chan> :abort! <fn>}
   so the caller can abort the request while it is in flight. Aborting resolves
   :response$ with {:isSuccess false :aborted? true}; aborting after completion
   is a no-op."
  [url & {:keys [headers timeout with-credentials?]}]
  (http-send {:url               url
              :method            "GET"
              :headers           headers
              :timeout           timeout
              :with-credentials? with-credentials?}))

(defn <http-post [url body & {:keys [headers timeout with-credentials?]}]
  (:response$ (http-send {:url               url
                          :method            "POST"
                          :body              body
                          :headers           headers
                          :timeout           timeout
                          :with-credentials? with-credentials?})))
