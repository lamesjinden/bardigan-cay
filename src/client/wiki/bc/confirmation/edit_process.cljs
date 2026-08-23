(ns wiki.bc.confirmation.edit-process
  (:require [cljs.core.async :as a]
            [wiki.bc.events.confirmation :as e-confirm]
            [wiki.bc.events.editing :as e-editing]))

(defn <create-editor-process [editing$ global-editing$]
  (a/go-loop [editing #{}]
             (let [[value channel] (a/alts! [editing$ global-editing$])]
               (condp = channel
                 editing$ (let [{:keys [id action]} value]
                            (recur (condp = action
                                     :start (conj editing id)
                                     :ending (let [out-chan (:out-chan value)]
                                               (if (contains? editing id)
                                                 (let [confirm$ (e-confirm/<notify-confirm)
                                                       response (a/<! confirm$)]
                                                   (a/>! out-chan response)
                                                   (if (= response :ok)
                                                     (disj editing id)
                                                     editing))
                                                 (do
                                                   (a/>! out-chan :ok)
                                                   editing)))
                                     :end (disj editing id)
                                     :default (throw (ex-info "unknown editing action" value)))))
                 global-editing$ (recur (let [{:keys [id action]} value]
                                          (condp = action
                                            :starting (let [out-chan (:out-chan value)]
                                                        (if (empty? editing)
                                                          (do
                                                            (a/>! out-chan :ok)
                                                            editing)
                                                          (let [confirm$ (e-confirm/<notify-confirm)
                                                                response (a/<! confirm$)]
                                                            (a/>! out-chan response)
                                                            editing)))
                                            :start (do
                                                     (e-editing/notify-editing-begin id)
                                                     editing)
                                            :ending (let [out-chan (:out-chan value)]
                                                      (e-editing/<notify-editing-ending id out-chan)
                                                      editing)
                                            :end (do
                                                   (e-editing/notify-editing-end id)
                                                   editing)
                                            :default (throw (ex-info "unknown global editing action" value)))))))))