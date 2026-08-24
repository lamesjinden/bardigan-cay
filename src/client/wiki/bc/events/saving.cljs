(ns wiki.bc.events.saving
  (:require [cljs.core.async :as a]))

(defonce ^:private saving-page$ (a/chan))
(defonce ^:private saving-page-mult$ (a/mult saving-page$))

(defn <notify-save-page [page-name data]
  (let [out-chan (a/promise-chan)]
    (a/put! saving-page$ {:page-name page-name
                          :data      data
                          :out-chan  out-chan})
    out-chan))

(defn create-saving-page$
  ([to-chan]
   (a/tap saving-page-mult$ to-chan)
   to-chan)
  ([] (create-saving-page$ (a/chan))))

(defonce ^:private saving-card$ (a/chan))
(defonce ^:private saving-card-mult$ (a/mult saving-card$))

(defn <notify-save-card [page-name hash data]
  (let [out-chan (a/promise-chan)]
    (a/put! saving-card$ {:page-name page-name
                          :hash      hash
                          :data      data
                          :out-chan  out-chan})
    out-chan))

(defn create-saving-card$
  ([to-chan]
   (a/tap saving-card-mult$ to-chan)
   to-chan)
  ([] (create-saving-card$ (a/chan))))

(defonce ^:private appending-page$ (a/chan))
(defonce ^:private appending-page-mult$ (a/mult appending-page$))

(defn <notify-append-page [page-name data]
  (let [out-chan (a/promise-chan)]
    (a/put! appending-page$ {:page-name page-name
                             :data      data
                             :out-chan  out-chan})
    out-chan))

(defn create-appending-page$
  ([to-chan]
   (a/tap appending-page-mult$ to-chan)
   to-chan)
  ([] (create-appending-page$ (a/chan))))
