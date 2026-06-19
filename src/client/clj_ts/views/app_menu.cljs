(ns clj-ts.views.app-menu
  (:require [reagent.core :as r]
            [clj-ts.theme :as theme]))

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
                          :theme-expanded? false})
        expand! (fn [] (swap! local-db assoc :expanded? true))
        collapse! (fn [] (swap! local-db assoc
                                :expanded? false
                                :theme-expanded? false))
        toggle-theme-submenu! (fn [] (swap! local-db update :theme-expanded? not))
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
             [:a.container.label {:href "/api/exportallpages"}
              [:span {:class [:material-symbols-sharp]} "deployed_code_update"]
              "Export All"]]
            [:li
             [:a.container.label.rss-link {:href "/api/rss/recentchanges"}
              [:span {:class [:material-symbols-sharp]} "rss_feed"]
              "RSS Feed"]]]]])])))
