(ns clj-ts.events.transcript
  (:require [cljs.core.async :as a]))

(defonce ^:private transcript-navigating$ (a/chan))
(defonce ^:private transcript-navigating-mult$ (a/mult transcript-navigating$))

(defn <notify-transcript-navigating [db]
  (let [out-chan (a/promise-chan)]
    (a/put! transcript-navigating$ {:out-chan out-chan
                                    :db db})
    out-chan))

(defn create-transcript-navigating$
  ([to-chan]
   (a/tap transcript-navigating-mult$ to-chan)
   to-chan)
  ([] (create-transcript-navigating$ (a/chan))))