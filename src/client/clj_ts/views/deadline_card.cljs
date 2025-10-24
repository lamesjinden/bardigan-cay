(ns clj-ts.views.deadline-card
  (:require [clojure.edn :as edn]
            [clj-ts.card :as card]
            ["date-fns" :as date-fns]))

(def deadline-defaults {:deadline/title-visible? true
                        :deadline/title "deadlines"})

(defn- datetime-past-due? [datetime]
  (date-fns/isPast datetime))

(defn- datetime-due-now? [datetime]
  ;; now == within 2 days
  (let [later-date datetime
        earlier-date (js/Date)
        difference (date-fns/differenceInDays later-date earlier-date)]
    (<= 0 difference 2)))

(defn- datetime-due-soon? [datetime]
  ;; soon == within 1 week
  (let [later-date datetime
        earlier-date (js/Date)
        difference (date-fns/differenceInDays later-date earlier-date)]
    (<= 0 difference 7)))

(defn- strip-pre-formatting [pre]
  (let [strip-leading-bullets (fn [s]
                                (if-some [match (re-matches #"^\s*[\*\+\-]\s+(.+)$" s)]
                                  (get match 1)
                                  s))
        strip-leading-numerals (fn [s]
                                 (if-some [match (re-matches #"^\s*\d+\.\s+(.+)$" s)]
                                   (get match 1)
                                   s))]
    (->> [strip-leading-bullets strip-leading-numerals]
         (reduce (fn [acc f]
                   (f acc))
                 pre))))

(defn- render-hit [{:keys [match datetime source-page] :as _hit}]
  (let [[_ pre _ post] match
        pre' (strip-pre-formatting pre)]
    (cond
      (datetime-past-due? datetime)
      [:div.deadline.past-due {:key (str source-page (first match))}
       [:span.pre pre']
       [:a.deadline-link {:href (str "/pages/" source-page)} "deadline:"]
       [:span.post post]]

      (datetime-due-now? datetime)
      [:div.deadline.due-now {:key (str source-page (first match))}
       [:span.pre pre']
       [:a.deadline-link {:href (str "/pages/" source-page)} "deadline:"]
       [:span.post post]]

      (datetime-due-soon? datetime)
      [:div.deadline.due-soon {:key (str source-page (first match))}
       [:span.pre pre']
       [:a.deadline-link {:href (str "/pages/" source-page)} "deadline:"]
       [:span.post post]]

      :else
      [:div.deadline.not-due {:key (str source-page (first match))}
       [:span.pre pre']
       [:a.deadline-link {:href (str "/pages/" source-page)} "deadline:"]
       [:span.post post]])))

(defn ->operator [x]
  (case x
    :> date-fns/isAfter
    :< date-fns/isBefore
    nil))

(defn ->long [x]
  (cond
    (string? x) (parse-long x)
    (number? x) x
    :else nil))

(defn ->units [x]
  (case x
    :days date-fns/addDays
    :hours date-fns/addHours
    :minutes date-fns/addMinutes
    nil))

(defn ->deadline-filter [[operator-token operand-token units-token]]
  (let [operator (->operator operator-token)
        magnitude (->long operand-token)
        add-units (->units units-token)]
    (when (and operator magnitude add-units)
      (fn [{:keys [_ datetime] :as _hit}]
        (let [now (js/Date)
              deadline-date datetime
              adjusted-date (add-units now magnitude)]
          (operator deadline-date adjusted-date))))))

(defn deadline [_db card]
  (let [card-configuration (merge deadline-defaults (card/->card-configuration card))
        hits (-> (get card "server_prepared_data")
                 (edn/read-string))
        deadline-filter (-> (:deadline/filter card-configuration)
                            (->deadline-filter)
                            (or identity))]
    [:div.deadline-root-container
     (when (:deadline/title-visible? card-configuration)
       [:h3 (:deadline/title card-configuration)])
     [:div.deadlines.container
      (if (seq hits)
        (->> hits
             (filter deadline-filter)
             (map render-hit))
        [:div.no-deadlines [:i "no deadlines"]])]]))