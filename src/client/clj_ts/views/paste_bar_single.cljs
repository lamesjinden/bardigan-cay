(ns clj-ts.views.paste-bar-single
  (:require [clj-ts.card :as cards]))

(defn- on-close-clicked [parent-db]
  (swap! parent-db assoc :mode :viewing))

(defn- on-save-clicked [db parent-db local-db]
  (let [card (:card @parent-db)
        new-body (->> @local-db :editor (.getValue))]
    (if-let [{:strs [source-page] :as _transcluded} (get card "transcluded")]
      (when-let [card-id (:card/id (cards/->card-configuration card))]
        (cards/replace-card-async! db source-page card-id new-body))
      (let [hash (get card "hash")]
        (cards/replace-card-async! db hash new-body)))))

(defn paste-bar-single
  [db parent-db local-db]
  [:div.pastebar
   [:div.edit-actions
    [:span.button-container
     [:button.big-btn.big-btn-left
      {:on-click (fn [] (on-close-clicked parent-db))}
      [:span {:class [:material-symbols-sharp :clickable]} "close"]]
     [:button.big-btn.big-btn-right
      {:on-click (fn [] (on-save-clicked db parent-db local-db))}
      [:span {:class [:material-symbols-sharp :clickable]} "save"]]]]])
