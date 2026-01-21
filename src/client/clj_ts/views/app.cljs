(ns clj-ts.views.app
  (:require
    [reagent.core :as r]
    [clj-ts.theme :as theme]
    [clj-ts.views.confirmation-dialog :refer [confirmation-dialog]]
    [clj-ts.views.app-header :refer [app-header]]
    [clj-ts.views.app-main :refer [app-main]]
    [clj-ts.views.app-page-controls :refer [app-page-controls]]
    [clj-ts.views.app-progress-bar :refer [app-progress-bar]]))

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
