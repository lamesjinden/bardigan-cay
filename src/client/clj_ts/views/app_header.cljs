(ns clj-ts.views.app-header
  (:require [reagent.core :as r]
            [clj-ts.views.nav-bar :refer [nav-bar]]
            [clj-ts.views.page-header :refer [page-header]]))

(defn app-header [db]
  (let [rx-nav-links (r/cursor db [:nav-links])
        rx-mode (r/cursor db [:mode])
        rx-current-page (r/cursor db [:current-page])]
    [:header.header-bar
     [nav-bar db rx-nav-links]
     [page-header db rx-mode rx-current-page]]))