(ns wiki.bc.views.lazy-graph
  (:require [reagent.core :as r]
            [wiki.bc.views.skeleton :refer [skeleton-card]]
            ["react" :as react]
            [shadow.lazy :as lazy]))

(def graph-card-component (let [loadable (shadow.lazy/loadable wiki.bc.views.graph/graph-card-component)]
                            (react/lazy
                             (fn []
                               (-> (lazy/load loadable)
                                   (.then (fn [root-el]
                                            #js {:default (r/reactify-component (fn [props]
                                                                                  [@loadable props]))})))))))

(defn suspended-graph-card-component [{:keys [db card]}]
  [:> react/Suspense {:fallback (r/as-element [skeleton-card])}
   [:> graph-card-component {:db db :card card}]])