(ns clj-ts.rendering.render-process
  (:require [cljs.core.async :as a]))

(defn <create-render-process [render$]
  (a/go-loop [state {}]
    (when-some [message (a/<! render$)]
      (let [{:keys [page-name hash action]} message]
        (recur
         (condp = action
           :render
           (if-let [out-chan (get-in state [page-name hash :scroll-into-view])]
             (do
               (a/>! out-chan :scroll-into-view)
               (assoc-in state [page-name hash] nil))
             state)

           :scroll-into-view
           (let [out-chan (:out-chan message)]
             (assoc-in state [page-name hash :scroll-into-view] out-chan))))))))
