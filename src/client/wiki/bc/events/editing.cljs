(ns wiki.bc.events.editing
  (:require [cljs.core.async :as a]))

;; region single edit eventing

(defonce ^:private editing$ (a/chan))
(defonce ^:private editing-mult$ (a/mult editing$))

(defn notify-editing-begin [id]
  (a/put! editing$ {:id     id
                    :action :start}))

(defn <notify-editing-ending
  ([id out-chan]
   (a/put! editing$ {:id       id
                     :action   :ending
                     :out-chan out-chan})
   out-chan)
  ([id]
   (let [out-chan (a/promise-chan)]
     (<notify-editing-ending id out-chan))))

(defn notify-editing-end [id]
  (a/put! editing$ {:id     id
                    :action :end}))

(defn create-editing$
  ([to-chan]
   (a/tap editing-mult$ to-chan)
   to-chan)
  ([] (create-editing$ (a/chan))))

;; endregion

;; region global edit eventing

(defonce ^:private editing-global$ (a/chan))
(defonce ^:private editing-global-mult$ (a/mult editing-global$))
(def global-editor-id "global")

(defn <notify-global-editing-starting []
  (let [out-chan (a/promise-chan)]
    (a/put! editing-global$ {:id       global-editor-id
                             :action   :starting
                             :out-chan out-chan})
    out-chan))

(defn notify-global-editing-start []
  (a/put! editing-global$ {:id     global-editor-id
                           :action :start}))

(defn <notify-global-editing-ending []
  (let [out-chan (a/promise-chan)]
    (a/put! editing-global$ {:id       global-editor-id
                             :action   :ending
                             :out-chan out-chan})
    out-chan))

(defn notify-global-editing-end []
  (a/put! editing-global$ {:id     global-editor-id
                           :action :end}))

(defn create-global-editing$
  ([to-chan]
   (a/tap editing-global-mult$ to-chan)
   to-chan)
  ([] (create-global-editing$ (a/chan))))

;; endregion