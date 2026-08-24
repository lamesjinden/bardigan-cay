(ns wiki.bc.events.jobs
  (:require [cljs.core.async :as a]))

(defonce ^:private jobs$ (a/chan))
(defonce ^:private jobs-mult$ (a/mult jobs$))

(defn notify-job-submit
  "Requests submission of a server job; label is the human name shown in
  the jobs list."
  [job-domain job-type label]
  (a/put! jobs$ {:action :submit
                 :job-domain job-domain
                 :job-type job-type
                 :label label}))

(defn notify-jobs-refresh
  "Requests a fresh fetch of job state from the server."
  []
  (a/put! jobs$ {:action :refresh}))

(defn create-jobs$
  ([to-chan]
   (a/tap jobs-mult$ to-chan)
   to-chan)
  ([] (create-jobs$ (a/chan))))

(defonce ^:private jobs-view-navigating$ (a/chan))
(defonce ^:private jobs-view-navigating-mult$ (a/mult jobs-view-navigating$))

(defn <notify-jobs-view-navigating [db]
  (let [out-chan (a/promise-chan)]
    (a/put! jobs-view-navigating$ {:out-chan out-chan
                                   :db db})
    out-chan))

(defn create-jobs-view-navigating$
  ([to-chan]
   (a/tap jobs-view-navigating-mult$ to-chan)
   to-chan)
  ([] (create-jobs-view-navigating$ (a/chan))))
