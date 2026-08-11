(ns clj-ts.events.jobs
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

(defn create-jobs$
  ([to-chan]
   (a/tap jobs-mult$ to-chan)
   to-chan)
  ([] (create-jobs$ (a/chan))))
