(ns clj-ts.views.app-menu
  (:require [reagent.core :as r]
            [clj-ts.events.jobs :as e-jobs]
            [clj-ts.theme :as theme]))

;; region jobs list

(defn- job-status-icon [status]
  [:span.job-status-icon {:class [:material-symbols-sharp (name status)]}
   (case status
     :accepted "schedule"
     :running "progress_activity"
     :success "check_circle"
     :failure "error"
     "help")])

(defn- job-list-item [{:keys [label status error-message output] :as _entry}]
  [:li.job-item {:class (name status)}
   [job-status-icon status]
   [:div.job-item-body
    [:span.job-label label]
    (when (= status :failure)
      [:span.job-error error-message])
    (when (and (= status :success) (seq (:failed-pages output)))
      [:span.job-warning
       {:title (str "failed pages: " (apply str (interpose ", " (:failed-pages output))))}
       (str (count (:failed-pages output)) " pages failed to export")])]
   (when (= status :success)
     [:a.job-download {:href (:url output)
                       :title (str "download " (:file-name output))}
      [:span {:class [:material-symbols-sharp]} "download"]])])

(defn- jobs-section [db]
  (let [{:keys [entries local-failures]} (:jobs @db)
        jobs (concat (reverse local-failures) entries)]
    [:li.jobs-section
     (if (seq jobs)
       [:ul.jobs-list
        (for [{:keys [job-id] :as entry} jobs]
          ^{:key job-id} [job-list-item entry])]
       [:div.jobs-empty "no jobs yet"])]))

;; endregion

(defn- theme-submenu-item [{:keys [label icon active? on-select]}]
  [:li.theme-submenu-item
   {:class (when active? "active")}
   [:span.container.label.clickable {:on-click on-select}
    [:span {:class [:material-symbols-sharp]} icon]
    label
    (when active?
      [:span.theme-submenu-check
       {:class [:material-symbols-sharp]} "check"])]])

(defn app-menu [_db _db-theme]
  (let [local-db (r/atom {:expanded? false
                          :theme-expanded? false
                          :jobs-expanded? false})
        expand! (fn [] (swap! local-db assoc :expanded? true))
        collapse! (fn [] (swap! local-db assoc
                                :expanded? false
                                :theme-expanded? false
                                :jobs-expanded? false))
        toggle-theme-submenu! (fn [] (swap! local-db update :theme-expanded? not))
        toggle-jobs-submenu! (fn [] (swap! local-db update :jobs-expanded? not))
        on-click (fn [e]
                   (expand!)
                   (.stopPropagation e))
        _ (js/document.addEventListener "click" (fn [e]
                                                  (when-let [specified-element (js/document.querySelector "#app-menu .menu-list")]
                                                    (let [click-inside? (.contains specified-element (.-target e))]
                                                      (when-not click-inside?
                                                        (collapse!))))))]
    (fn [db db-theme]
      [:div#app-menu
       [:span.clickable {:class    [:material-symbols-sharp]
                         :on-click (fn [e] (on-click e))} "menu"]
       (when (:expanded? @local-db)
         [:div.app-menu-outer
          [:div.app-menu-container
           [:ul.menu-list
            [:li.theme-submenu-parent
             [:span.container.label.clickable {:on-click toggle-theme-submenu!}
              [:span {:class [:material-symbols-sharp]} "palette"]
              "Theme"
              [:span.theme-submenu-chevron
               {:class [:material-symbols-sharp]}
               (if (:theme-expanded? @local-db) "expand_less" "expand_more")]]]
            (when (:theme-expanded? @local-db)
              [:<>
               [theme-submenu-item {:label "Light"
                                    :icon "light_mode"
                                    :active? (theme/light-theme? db-theme)
                                    :on-select (fn []
                                                 (theme/set-light-theme! db)
                                                 (collapse!))}]
               [theme-submenu-item {:label "Dark"
                                    :icon "dark_mode"
                                    :active? (theme/dark-theme? db-theme)
                                    :on-select (fn []
                                                 (theme/set-dark-theme! db)
                                                 (collapse!))}]
               [theme-submenu-item {:label "SynthWave84"
                                    :icon "bolt"
                                    :active? (theme/synthwave84-theme? db-theme)
                                    :on-select (fn []
                                                 (theme/set-synthwave84-theme! db)
                                                 (collapse!))}]])
            [:li
             [:span.container.label.clickable
              {:on-click (fn []
                           (e-jobs/notify-job-submit "export" "all-pages" "Export All Pages"))}
              [:span {:class [:material-symbols-sharp]} "deployed_code_update"]
              "Export All"]]
            [:li.jobs-submenu-parent
             [:span.container.label.clickable {:on-click toggle-jobs-submenu!}
              [:span {:class [:material-symbols-sharp]} "work_history"]
              "Jobs"
              [:span.theme-submenu-chevron
               {:class [:material-symbols-sharp]}
               (if (:jobs-expanded? @local-db) "expand_less" "expand_more")]]]
            (when (:jobs-expanded? @local-db)
              [jobs-section db])
            [:li
             [:a.container.label.rss-link {:href "/api/rss/recentchanges"}
              [:span {:class [:material-symbols-sharp]} "rss_feed"]
              "RSS Feed"]]]]])])))
