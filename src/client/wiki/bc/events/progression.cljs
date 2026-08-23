(ns wiki.bc.events.progression
  (:require [cljs.core.async :as a]))

(defonce ^:private progress$ (a/chan))
(defonce ^:private progress-mult$ (a/mult progress$))

(defn notify-progress-begin [id]
  (a/put! progress$ {:id     id
                     :action :start}))

(defn notify-progress-update [id percent-completed]
  (a/put! progress$ {:id        id
                     :action    :update
                     :completed percent-completed}))

(defn notify-progress-end [id]
  (a/put! progress$ {:id     id
                     :action :end}))

(defn notify-progress-fail [id]
  (a/put! progress$ {:id     id
                     :action :fail}))

(defn create-progress$
  ([to-chan]
   (a/tap progress-mult$ to-chan)
   to-chan)
  ([] (create-progress$ (a/chan))))