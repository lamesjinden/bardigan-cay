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
        ;; todo
      [:div.deadline.past-due {:key (str source-page (first match))}
       [:span.pre pre']
       [:a.deadline-link {:href (str "/pages/" source-page)} "deadline:"]
       [:span.post post]]

      (datetime-due-now? datetime)
        ;; todo
      [:div.deadline.due-now {:key (str source-page (first match))}
       [:span.pre pre']
       [:a.deadline-link {:href (str "/pages/" source-page)} "deadline:"]
       [:span.post post]]

      (datetime-due-soon? datetime)
        ;; todo
      [:div.deadline.due-soon {:key (str source-page (first match))}
       [:span.pre pre']
       [:a.deadline-link {:href (str "/pages/" source-page)} "deadline:"]
       [:span.post post]]

      :else
      [:div.deadline.not-due {:key (str source-page (first match))}
       [:span.pre pre']
       [:a.deadline-link {:href (str "/pages/" source-page)} "deadline:"]
       [:span.post post]])))

(defn deadline [_db card]
  (let [card-configuration (merge deadline-defaults (card/->card-configuration card))
        hits (-> (get card "server_prepared_data")
                 (edn/read-string))]
    [:div.deadline-root-container
     (when (:deadline/title-visible? card-configuration)
       [:h3 (:deadline/title card-configuration)])
     [:div.deadlines.container
      (if (seq hits)
        (map render-hit hits)
        [:div.no-deadlines [:i "no deadlines"]])]]))