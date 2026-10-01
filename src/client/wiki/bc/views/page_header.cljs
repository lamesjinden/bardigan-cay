(ns wiki.bc.views.page-header
  (:require [reagent.core :as r]
            [wiki.bc.revision :as revision]
            [wiki.bc.temporal :as temporal]
            [wiki.bc.views.revision-list :refer [revision-list]]
            [wiki.bc.views.tool-bar :refer [tool-bar]]))

(defn- step-button [db entry icon title]
  [:span {:class    [:material-symbols-sharp
                     (if entry :clickable :disabled)]
          :title    title
          :on-click (fn []
                      (when entry
                        (revision/<view-revision! db (revision/->sha entry))))}
   icon])

;; leads the banner so the step buttons hold still under the pointer
;; while the commit details beside them change length
(defn- snapshot-position [db {:keys [number total older newer]}]
  [:span.snapshot-banner-position
   [step-button db older "navigate_before" "Older revision"]
   [:span (str "Revision " number " of " total)]
   [step-button db newer "navigate_next" "Newer revision"]])

(defn- snapshot-banner [db revision entries]
  [:div.snapshot-banner
   [:span {:class [:material-symbols-sharp]} "history"]
   (when-let [position (revision/position entries revision)]
     [snapshot-position db position])
   [:span "Read-only snapshot at"]
   [:span.snapshot-banner-sha (revision/->short-sha revision)]
   [:span.snapshot-banner-date (temporal/format-locale (revision/->date revision))]
   [:span.snapshot-banner-author (revision/->author revision)]
   [:span.snapshot-banner-message (revision/->message revision)]])

;; a snapshot from before a rename is titled as the page was then named,
;; and marked as renamed (its present name is the marker's tooltip)
(defn- page-title [page-name revision]
  (if-let [former-name (revision/former-name revision page-name)]
    [:h1 former-name
     [:span.page-title-renamed {:title (str "Now named " page-name)}
      "(renamed)"]]
    [:h1 page-name]))

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
          (if transcript?
            [:h1 "Transcript"]
            [page-title @db-current-page revision])]
         [tool-bar db db-mode db-current-page]
         (when (and viewing? snapshot?)
           [snapshot-banner db revision (:entries @rx-revisions)])
         (when (and viewing? (:open? @rx-revisions))
           [revision-list db rx-revisions rx-revision])]))))
