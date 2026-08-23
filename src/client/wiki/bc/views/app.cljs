(ns wiki.bc.views.app
  (:require
    [reagent.core :as r]
    [wiki.bc.theme :as theme]
    [wiki.bc.views.confirmation-dialog :refer [confirmation-dialog]]
    [wiki.bc.views.app-header :refer [app-header]]
    [wiki.bc.views.app-main :refer [app-main]]
    [wiki.bc.views.app-page-controls :refer [app-page-controls]]
    [wiki.bc.views.app-progress-bar :refer [app-progress-bar]]))

(defn app [db confirmation-request$ progress$]
  (reagent.core/track! (partial theme/toggle-app-theme db))

  (let [rx-mode (r/cursor db [:mode])
        rx-quake-mode? (r/cursor db [:quake-mode?])]
    [:div.app-container {:class (when @rx-quake-mode? "quake-mode")}
     [confirmation-dialog confirmation-request$]
     [app-progress-bar db progress$]
     [app-header db]
     [app-page-controls db rx-mode]
     [app-main db]]))
