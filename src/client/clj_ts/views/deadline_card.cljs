(ns clj-ts.views.deadline-card
  (:require
   [clj-ts.card :as card]))

(def deadline-defaults {:deadline/title-visible? true
                        :deadline/title "deadlines"})

(defn deadline [_db card]
  (let [card-configuration (merge deadline-defaults (card/->card-configuration card))
        server-prepared-data (get card "server_prepared_data")]
    [:div.deadline-root-container
     (when (:deadline/title-visible? card-configuration)
       [:h3 (:deadline/title card-configuration)])
     [:div {:dangerouslySetInnerHTML {:__html server-prepared-data}}]]))
