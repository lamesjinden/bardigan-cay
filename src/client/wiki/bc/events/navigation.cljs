(ns wiki.bc.events.navigation
  (:require [cljs.core.async :as a]))

(defonce ^:private navigating$ (a/chan))
(defonce ^:private navigating-mult$ (a/mult navigating$))

(defn <notify-navigating
  "Requests navigation to page-name, at the git revision rev when given
   (nil for the live page); the returned promise-chan delivers the
   outcome."
  ([page-name rev]
   (let [out-chan (a/promise-chan)]
     (a/put! navigating$ {:page-name page-name
                          :rev       rev
                          :out-chan  out-chan})
     out-chan))
  ([page-name]
   (<notify-navigating page-name nil)))

(defn create-navigating$
  ([to-chan]
   (a/tap navigating-mult$ to-chan)
   to-chan)
  ([] (create-navigating$ (a/chan))))