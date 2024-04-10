(ns clj-ts.events.rendering
  (:require [cljs.core.async :as a]))

(defonce ^:private render$ (a/chan))
(defonce ^:private render-mult$ (a/mult render$))

(defn notify-render [page-name hash]
  (a/put! render$ {:page-name page-name
                   :hash hash
                   :action :render}))

(defn <notify-scroll-into-view [page-name hash]
  (let [out-chan (a/promise-chan)]
    (a/put! render$ {:page-name page-name
                     :hash hash
                     :action :scroll-into-view
                     :out-chan out-chan})
    out-chan))

(defn create-rendering$
  ([to-chan]
   (a/tap render-mult$ to-chan)
   to-chan)
  ([] (create-rendering$ (a/chan))))