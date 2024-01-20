(ns clj-ts.views.app-page-controls
  (:require [clj-ts.events.expansion :as e-expansion]
            [clj-ts.mode :as mode]))

(defn expand-all-cards []
  (e-expansion/notify-expansion :expanded))

(defn collapse-all-cards []
  (e-expansion/notify-expansion :collapsed))

(defn app-page-controls [_db db-mode]
  (when (mode/viewing? db-mode)
    [:div.page-controls-container
     [:span {:class    [:material-symbols-sharp :clickable :left]
             :on-click (fn [] (expand-all-cards))} "unfold_more_double"]
     [:span {:class    [:material-symbols-sharp :clickable :right]
             :on-click (fn [] (collapse-all-cards))} "unfold_less_double"]]))