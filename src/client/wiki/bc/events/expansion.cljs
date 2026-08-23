(ns wiki.bc.events.expansion
  (:require [cljs.core.async :as a]))

(defonce ^:private expansion$ (a/chan))
(defonce ^:private expansion-mult$ (a/mult expansion$))

(defn notify-expansion [expanded-or-collapsed]
  (a/put! expansion$ expanded-or-collapsed))

(defn create-expansion$
  ([to-chan]
   (a/tap expansion-mult$ to-chan)
   to-chan)
  ([] (create-expansion$ (a/chan))))