(ns wiki.bc.views.revision-list
  (:require [reagent.core :as r]
            [wiki.bc.keyboard :as keyboard]
            [wiki.bc.revision :as revision]
            [wiki.bc.temporal :as temporal]))

(defn- revision-item [db current-sha entry]
  (let [sha (revision/->sha entry)]
    [:li.revision-item
     {:class    (when (= sha current-sha) "current")
      :title    sha
      :on-click (fn [] (revision/<view-revision! db sha))}
     [:span.revision-sha (revision/->short-sha entry)]
     [:span.revision-date (temporal/format-locale (revision/->date entry))]
     [:span.revision-author (revision/->author entry)]
     [:span.revision-message (revision/->message entry)]
     (when (revision/->head? entry)
       [:span.revision-tag "HEAD"])]))

;; the list is the styled tray, so an empty history shows as its only entry
(defn- revision-entries [db entries current-sha]
  [:ul.revision-list
   (if (empty? entries)
     [:li.revision-list-status "No committed revisions of this page."]
     (for [entry entries]
       ^{:key (revision/->sha entry)}
       [revision-item db current-sha entry]))])

(defn- on-key-up [db e]
  ;; note - escape doesn't fire for key-press, only key-up
  (when (= (.-keyCode e) keyboard/key-escape-code)
    (revision/close-revision-list! db)))

(defn revision-list [db _db-revisions _db-revision]
  (let [key-up-listener (partial on-key-up db)]
    (r/create-class
     {:component-did-mount    (fn [] (js/window.addEventListener "keyup" key-up-listener))
      :component-will-unmount (fn [] (js/window.removeEventListener "keyup" key-up-listener))
      :reagent-render
      (fn [db db-revisions db-revision]
        (let [{:keys [entries]} @db-revisions
              current-sha (revision/->sha @db-revision)]
          [:div.revision-list-container
           [revision-entries db entries current-sha]]))})))
