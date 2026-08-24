(ns wiki.bc.request-process
  "Constructor for switching request processes: the latest event on a stream
   supersedes an in-flight request (its request is aborted and its out-chan
   is closed, so the requester's continuation never runs). Superseding is
   client-side only - the server still processes every request."
  (:require [cljs.core.async :as a]
            [wiki.bc.async :as async]))

(defn- close-superseded! [{:keys [out-chan]}]
  (a/close! out-chan))

(defn- route-responses! [switched$]
  (a/go-loop []
    (when-some [{:keys [input response]} (a/<! switched$)]
      (a/put! (:out-chan input) response)
      (recur))))

(defn create-request-process
  "Wires a stream of {:out-chan ..}-carrying events through
   async/create-switching-channel: each event's response is delivered to its
   out-chan; a superseded event's out-chan is closed instead.

   start-request: event -> {:response$ <promise-chan> :abort! <fn>}"
  [source$ start-request]
  (-> source$
      (async/create-switching-channel start-request :on-supersede! close-superseded!)
      (route-responses!)))
