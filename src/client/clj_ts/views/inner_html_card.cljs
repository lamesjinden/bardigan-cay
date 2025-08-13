(ns clj-ts.views.inner-html-card
  (:require [reagent.core :as r]
            [clj-ts.highlight :as highlight]))

(defn- apply-highlighting [element]
  (let [child-selector "pre code"
        selecteds (-> element
                      (.querySelectorAll child-selector)
                      (js/Array.from)
                      (array-seq))]
    (doseq [selected selecteds]
      ;; carefully apply highlighting to children; 
      ;; avoids extra calls to highlightAll, which writes warnings to console
      (highlight/highlight-element selected))))

(defn inner-html [_s]
  (let [!root-element (r/atom nil)]
    (r/create-class
     {:component-did-mount (fn [_this] (apply-highlighting @!root-element))
      :reagent-render (fn [s]
                        [:div {:ref (fn [element] (reset! !root-element element))
                               :dangerouslySetInnerHTML {:__html s}}])})))