(ns wiki.bc.views.lazy-workspace-card
  (:require [reagent.core :as r]
            [wiki.bc.views.skeleton :refer [skeleton-card]]
            ["react" :as react]
            [shadow.lazy :as lazy]))

(def workspace-component (let [loadable (shadow.lazy/loadable wiki.bc.views.workspace-card/workspace-component)]
                           (react/lazy
                            (fn []
                              (-> (lazy/load loadable)
                                  (.then (fn [root-el]
                                           #js {:default (r/reactify-component (fn [props]
                                                                                 [@loadable props]))})))))))

(defn suspended-workspace-component [{:keys [db card]}]
  [:> react/Suspense {:fallback (r/as-element [skeleton-card])}
   [:> workspace-component {:db db :card card}]])