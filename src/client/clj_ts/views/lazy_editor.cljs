(ns clj-ts.views.lazy-editor
  (:require [reagent.core :as r]
            [clj-ts.views.skeleton :refer [skeleton-card]]
            ["react" :as react]
            [shadow.lazy :as lazy]))

(def editor-component (let [loadable (shadow.lazy/loadable clj-ts.views.editor/editor-component)]
                        (react/lazy
                         (fn []
                           (-> (lazy/load loadable)
                               (.then (fn [root-el]
                                        #js {:default (r/reactify-component (fn [props]
                                                                              [@loadable props]))})))))))

(defn suspended-editor-component [{:keys [db db-raw]}]
  [:> react/Suspense {:fallback (r/as-element [skeleton-card])}
   [:> editor-component {:db db :dbRaw db-raw}]])