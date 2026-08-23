(ns wiki.bc.views.app-header
  (:require [reagent.core :as r]
            [wiki.bc.views.nav-bar :refer [nav-bar]]
            [wiki.bc.views.page-header :refer [page-header]]))

(defn app-header [db]
  (let [rx-nav-links (r/cursor db [:nav-links])
        rx-mode (r/cursor db [:mode])
        rx-current-page (r/cursor db [:current-page])
        rx-quake-mode? (r/cursor db [:quake-mode?])]
    [:header.header-bar
     [nav-bar db rx-nav-links rx-quake-mode?]
     [page-header db rx-mode rx-current-page]]))