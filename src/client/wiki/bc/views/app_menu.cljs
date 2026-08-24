(ns wiki.bc.views.app-menu
  (:require [reagent.core :as r]
            [wiki.bc.events.jobs :as e-jobs]
            [wiki.bc.jobs :as jobs]
            [wiki.bc.theme :as theme]))

(defn- submenu-item [{:keys [label icon icon-class active? on-select]}]
  [:li.submenu-item
   {:class (when active? "active")}
   [:span.container.label.clickable {:on-click on-select}
    [:span {:class [:material-symbols-sharp icon-class]} icon]
    label
    (when active?
      [:span.submenu-check
       {:class [:material-symbols-sharp]} "check"])]])

(defn- submenu-parent [{:keys [label icon expanded? on-toggle]}]
  [:li.submenu-parent
   [:span.container.label.clickable {:on-click on-toggle}
    [:span {:class [:material-symbols-sharp]} icon]
    label
    [:span.submenu-chevron
     {:class [:material-symbols-sharp]}
     (if expanded? "expand_less" "expand_more")]]])

(defn app-menu [_db _db-theme]
  (let [local-db (r/atom {:expanded? false
                          :theme-expanded? false
                          :export-expanded? false})
        expand! (fn [] (swap! local-db assoc :expanded? true))
        collapse! (fn [] (swap! local-db assoc
                                :expanded? false
                                :theme-expanded? false
                                :export-expanded? false))
        toggle-theme-submenu! (fn [] (swap! local-db update :theme-expanded? not))
        toggle-export-submenu! (fn [] (swap! local-db update :export-expanded? not))
        on-click (fn [e]
                   (expand!)
                   (.stopPropagation e))
        _ (js/document.addEventListener "click" (fn [e]
                                                  (when-let [specified-element (js/document.querySelector "#app-menu .menu-list")]
                                                    (let [click-inside? (.contains specified-element (.-target e))]
                                                      (when-not click-inside?
                                                        (collapse!))))))]
    (fn [db db-theme]
      (let [{:keys [attention?] :as jobs-state} (:jobs @db)
            jobs-active? (jobs/any-active? jobs-state)]
        [:div#app-menu
         [:span.app-menu-toggle
          {:class (when attention? "jobs-attention")}
          [:span.clickable {:class    [:material-symbols-sharp]
                            :on-click (fn [e] (on-click e))} "menu"]]
         (when (:expanded? @local-db)
           [:div.app-menu-outer
            [:div.app-menu-container
             [:ul.menu-list
              [submenu-parent {:label "Theme"
                               :icon "palette"
                               :expanded? (:theme-expanded? @local-db)
                               :on-toggle toggle-theme-submenu!}]
              (when (:theme-expanded? @local-db)
                [:<>
                 [submenu-item {:label "Light"
                                :icon "light_mode"
                                :active? (theme/light-theme? db-theme)
                                :on-select (fn []
                                             (theme/set-light-theme! db)
                                             (collapse!))}]
                 [submenu-item {:label "Dark"
                                :icon "dark_mode"
                                :active? (theme/dark-theme? db-theme)
                                :on-select (fn []
                                             (theme/set-dark-theme! db)
                                             (collapse!))}]
                 [submenu-item {:label "SynthWave84"
                                :icon "bolt"
                                :active? (theme/synthwave84-theme? db-theme)
                                :on-select (fn []
                                             (theme/set-synthwave84-theme! db)
                                             (collapse!))}]])
              [submenu-parent {:label "Export"
                               :icon "output"
                               :expanded? (:export-expanded? @local-db)
                               :on-toggle toggle-export-submenu!}]
              (when (:export-expanded? @local-db)
                [:<>
                 ;; the menu stays open on submit so the spinning Export
                 ;; Status icon can acknowledge the running job
                 [submenu-item {:label "Export All"
                                :icon "deployed_code_update"
                                :on-select (fn []
                                             (e-jobs/notify-job-submit "export" "all-pages" "Export All Pages"))}]
                 [submenu-item {:label "Export Status"
                                :icon (if jobs-active?
                                        "progress_activity"
                                        "work_history")
                                :icon-class (when jobs-active? "spinning")
                                :on-select (fn []
                                             (e-jobs/<notify-jobs-view-navigating db)
                                             (collapse!))}]])
              [:li
               [:a.container.label.rss-link {:href "/api/rss/recentchanges"}
                [:span {:class [:material-symbols-sharp]} "rss_feed"]
                "RSS Feed"]]]]])]))))
