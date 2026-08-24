(ns wiki.bc.events.cards
  (:require [cljs.core.async :as a]))

(defonce ^:private moving-card$ (a/chan))
(defonce ^:private moving-card-mult$ (a/mult moving-card$))

(defn <notify-move-card [from-page to-page hash]
  (let [out-chan (a/promise-chan)]
    (a/put! moving-card$ {:from-page from-page
                          :to-page   to-page
                          :hash      hash
                          :out-chan  out-chan})
    out-chan))

(defn create-moving-card$
  ([to-chan]
   (a/tap moving-card-mult$ to-chan)
   to-chan)
  ([] (create-moving-card$ (a/chan))))

(defonce ^:private reordering-card$ (a/chan))
(defonce ^:private reordering-card-mult$ (a/mult reordering-card$))

(defn <notify-reorder-card [page-name hash direction]
  (let [out-chan (a/promise-chan)]
    (a/put! reordering-card$ {:page-name page-name
                              :hash      hash
                              :direction direction
                              :out-chan  out-chan})
    out-chan))

(defn create-reordering-card$
  ([to-chan]
   (a/tap reordering-card-mult$ to-chan)
   to-chan)
  ([] (create-reordering-card$ (a/chan))))
