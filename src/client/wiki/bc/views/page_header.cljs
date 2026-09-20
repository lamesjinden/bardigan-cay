(ns wiki.bc.views.page-header
  (:require [reagent.core :as r]
            [wiki.bc.revision :as revision]
            [wiki.bc.temporal :as temporal]
            [wiki.bc.views.revision-list :refer [revision-list]]
            [wiki.bc.views.tool-bar :refer [tool-bar]]))

(defn- snapshot-banner [revision]
  [:div.snapshot-banner
   [:span {:class [:material-symbols-sharp]} "history"]
   [:span "Read-only snapshot at"]
   [:span.snapshot-banner-sha (revision/->short-sha revision)]
   [:span.snapshot-banner-date (temporal/format-locale (revision/->date revision))]
   [:span.snapshot-banner-author (revision/->author revision)]
   [:span.snapshot-banner-message (revision/->message revision)]])

(defn page-header [db db-mode db-current-page]
  (let [rx-revision (r/cursor db [:revision])
        rx-revisions (r/cursor db [:revisions])]
    (fn [db db-mode db-current-page]
      (let [mode @db-mode
            transcript? (= mode :transcript)
            viewing? (= mode :viewing)
            revision @rx-revision
            snapshot? (some? revision)]
        [:div.page-header-container {:class (when snapshot? "snapshot")}
         [:div.page-title-container
          [:h1 (if transcript?
                 "Transcript"
                 @db-current-page)]]
         [tool-bar db db-mode db-current-page]
         (when (and viewing? snapshot?)
           [snapshot-banner revision])
         (when (and viewing? (:open? @rx-revisions))
           [revision-list db rx-revisions rx-revision])]))))
