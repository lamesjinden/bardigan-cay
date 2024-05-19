(ns clj-ts.views.lazy-editor-single
  (:require [reagent.core :as r]
            [clj-ts.views.skeleton :refer [skeleton-card]]
            ["react" :as react]
            [shadow.lazy :as lazy]))

(def editor-component (let [loadable (shadow.lazy/loadable clj-ts.views.editor-single/single-editor-component)]
                        (react/lazy
                         (fn []
                           (-> (lazy/load loadable)
                               (.then (fn [root-el]
                                        #js {:default (r/reactify-component (fn [props]
                                                                              [@loadable props]))})))))))

(defn suspended-editor-component [db db-theme parent-db !editor-element]
  [:> react/Suspense {:fallback (r/as-element [skeleton-card])}
   [:> editor-component {:db db :db-theme db-theme :parent-db parent-db :editor-element !editor-element}]])