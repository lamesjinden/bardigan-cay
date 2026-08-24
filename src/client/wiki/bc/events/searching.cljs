(ns wiki.bc.events.searching
  (:require [cljs.core.async :as a]))

(defonce ^:private searching$ (a/chan))
(defonce ^:private searching-mult$ (a/mult searching$))

(defn <notify-search [query]
  (let [out-chan (a/promise-chan)]
    (a/put! searching$ {:query    query
                        :out-chan out-chan})
    out-chan))

(defn create-searching$
  ([to-chan]
   (a/tap searching-mult$ to-chan)
   to-chan)
  ([] (create-searching$ (a/chan))))
