(ns wiki.bc.views.page-header
  (:require [wiki.bc.views.tool-bar :refer [tool-bar]]))

(defn page-header [db db-mode db-current-page]
  (let [transcript? (= @db-mode :transcript)]
    (if transcript?
      [:div.page-header-container
       [:div.page-title-container
        [:h1 "Transcript"]]
       [tool-bar db db-mode db-current-page]]
      [:div.page-header-container
       [:div.page-title-container
        [:h1 @db-current-page]]
       [tool-bar db db-mode db-current-page]])))
