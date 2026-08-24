(ns wiki.bc.confirmation.jobs-view-process
  (:require [cljs.core.async :as a]
            [wiki.bc.events.confirmation :as e-confirm]
            [wiki.bc.jobs :as jobs]
            [wiki.bc.navigation :as nav]))

(defn- update-edit-sessions [editing {:keys [id action]}]
  (condp = action
    :start (conj editing id)
    :end (disj editing id)
    editing))

(defn- activate-jobs-view! [db]
  (nav/push-state {:mode "jobs"} "/#jobs")
  (jobs/enter-jobs-view! db))

(defn <create-jobs-view-process [jobs-view-navigating$ editing$]
  (a/go-loop [editing #{}]
    (let [[value channel] (a/alts! [jobs-view-navigating$ editing$])]
      (condp = channel
        jobs-view-navigating$ (let [{:keys [out-chan db]} value]
                                (if (empty? editing)
                                  (do
                                    (activate-jobs-view! db)
                                    (a/put! out-chan true)
                                    (a/close! out-chan)
                                    (recur editing))
                                  (let [confirm$ (e-confirm/<notify-confirm)
                                        response (a/<! confirm$)]
                                    (if (= response :ok)
                                      (do
                                        (activate-jobs-view! db)
                                        (a/put! out-chan true)
                                        (a/close! out-chan)
                                        (recur #{}))
                                      (recur editing)))))
        editing$ (recur (update-edit-sessions editing value))))))
