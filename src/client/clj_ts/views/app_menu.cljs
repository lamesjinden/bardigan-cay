(ns clj-ts.views.app-menu
  (:require [reagent.core :as r]
            [clj-ts.theme :as theme]))

(defn app-menu [_db _db-theme]
  (let [local-db (r/atom {:expanded? false})
        expand! (fn [] (swap! local-db assoc :expanded? true))
        collapse! (fn [] (swap! local-db assoc :expanded? false))
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
            (if (theme/light-theme? db-theme)
              [:li
               [:span {:class [:material-symbols-sharp]} "dark_mode"]
               [:span.label {:on-click (fn []
                                         (theme/set-dark-theme! db)
                                         (collapse!))}
                "Switch Theme"]]
              [:li
               [:span {:class [:material-symbols-sharp]} "light_mode"]
               [:span.label {:on-click (fn []
                                         (theme/set-light-theme! db)
                                         (collapse!))}
                "Switch Theme"]])
            [:li
             [:span {:class [:material-symbols-sharp]} "deployed_code_update"]
             [:a.label {:href "/api/exportallpages"} "Export All"]]
            [:li
             [:span {:class [:material-symbols-sharp]} "rss_feed"]
             [:a.label.rss-link {:href "/api/rss/recentchanges"} "RSS Feed"]]]]])])))
