(ns clj-ts.views.app-progress-bar
  (:require [cljs.core.async :as a]
            [reagent.core :as r]))

(defn- ->% [x]
  (str x "%"))

(defn app-progress-bar [_db progress$]
  (let [local-db (r/atom {:width   0
                          :opacity 0})]
    ;; the bar tracks one request at a time, identified by its id (the request
    ;; url); ticking is local to this loop rather than routed through the
    ;; progress events, so a superseded request cannot leave a stale ticker
    ;; behind — and only the tracked id may update, complete, or fail the bar
    (a/go-loop [task-id nil
                completed 0]
      (let [ports (cond-> [progress$]
                    (some? task-id) (conj (a/timeout 1000)))
            [message channel] (a/alts! ports :priority true)]
        (if (= channel progress$)
          (when-some [{:keys [id action]} message]
            (condp = action
              :start (let [starting-completed 10]
                       (swap! local-db assoc :opacity (->% 100) :width (->% starting-completed))
                       (swap! local-db dissoc :completed? :failed?)
                       (recur id starting-completed))
              :update (if (= id task-id)
                        (let [completed' (min (:completed message) 100)]
                          (swap! local-db assoc :width (->% completed'))
                          (recur task-id completed'))
                        (recur task-id completed))
              :end (if (= id task-id)
                     (do
                       (swap! local-db assoc :completed? true)
                       (recur nil 0))
                     (recur task-id completed))
              ;; a failure is surfaced whether or not the failing request still
              ;; owns the bar — the red flash is the app's only failure feedback —
              ;; but only the tracked request's failure stops the ticker
              :fail (do
                      (swap! local-db assoc :failed? true)
                      (if (= id task-id)
                        (recur nil 0)
                        (recur task-id completed)))
              (recur task-id completed)))
          ;; tick: creep the bar while a request is in flight
          (let [completed' (min (inc completed) 100)]
            (swap! local-db assoc :width (->% completed'))
            (recur task-id completed')))))

    (fn [_db _progress$]
      (let [completed? (:completed? @local-db)
            failed? (:failed? @local-db)]

        [:span.progress-bar-outer {:style {:opacity (cond
                                                      completed? 0
                                                      failed? 0
                                                      :else (:opacity @local-db))}
                                   :class (cond
                                            completed? "completed"
                                            failed? "failed"
                                            :else "")}
         [:span.progress-bar-inner {:style {:width (:width @local-db)}
                                    :class (cond
                                             completed? "completed"
                                             failed? "failed"
                                             :else "")}]]))))
