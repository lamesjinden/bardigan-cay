(ns clj-ts.views.app-progress-bar
  (:require [cljs.core.async :as a]
            [reagent.core :as r]
            [clj-ts.events.progression :as e-progress]))

(defn- ->% [x]
  (str x "%"))

(defn- <timeout [ms]
  (let [c (a/chan)]
    (js/setTimeout (fn [] (a/close! c)) ms)
    c))

(defn schedule-update [id completed]
  (a/go
    (a/<! (<timeout 1000))
    (e-progress/notify-progress-update id completed)))

(defn app-progress-bar [_db progress$]
  (let [local-db (r/atom {:width   0
                          :opacity 0})]
    (a/go-loop [tasks {}]
      (when-some [message (a/<! progress$)]
        (let [{:keys [id action]} message]
          (recur
           (condp = action
             :start (let [starting-completed 10]
                      (swap! local-db assoc :opacity (->% 100) :width (->% starting-completed))
                      (swap! local-db dissoc :completed? :failed?)
                      (schedule-update id (inc starting-completed))
                      {id starting-completed})
             :update (if-let [_current-completed (get tasks id)]
                       (let [{completed :completed} message
                             completed' (min completed 100)]
                         (swap! local-db assoc :width (->% completed'))
                         (schedule-update id (inc completed'))
                         {id completed'})
                       tasks)
             :end (do
                    (swap! local-db assoc :completed? true)
                    {})
             :fail (do
                     (swap! local-db assoc :failed? true)
                     {})
             tasks)))))

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
