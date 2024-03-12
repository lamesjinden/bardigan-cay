(ns clj-ts.confirmation.transcript-process
  (:require [cljs.core.async :as a]
            [clj-ts.events.confirmation :as e-confirm]
            [clj-ts.mode :as mode]
            [clj-ts.navigation :as nav]))

(defn- update-edit-sessions [editing {:keys [id action]}]
  (condp = action
    :start (conj editing id)
    :end (disj editing id)
    editing))

(defn <create-transcript-process [transcript-navigating$ editing$]
  (a/go-loop [editing #{}]
    (let [[value channel] (a/alts! [transcript-navigating$ editing$])]
      (condp = channel
        transcript-navigating$ (let [{:keys [out-chan db]} value]
                                 (if (empty? editing)
                                   (do
                                     (nav/push-state {:mode "transcript"} "/#transcript")
                                     (mode/set-transcript-mode! db)
                                     (a/put! out-chan true)
                                     (a/close! out-chan)
                                     (recur editing))
                                   (let [confirm$ (e-confirm/<notify-confirm)
                                         response (a/<! confirm$)]
                                     (if (= response :ok)
                                       (do
                                         (nav/push-state {:mode "transcript"} "/#transcript")
                                         (mode/set-transcript-mode! db)
                                         (a/put! out-chan true)
                                         (a/close! out-chan)
                                         (recur #{}))
                                       (recur editing)))))
        editing$ (recur (update-edit-sessions editing value))))))
