(ns wiki.bc.views.inner-html-card
  (:require [reagent.core :as r]
            [shadow.lazy :as lazy]
            [wiki.bc.highlight :as highlight]))

(defn- apply-highlighting [element]
  (let [child-selector "pre code:not(.mermaid)"
        selecteds (-> element
                      (.querySelectorAll child-selector)
                      (js/Array.from)
                      (array-seq))]
    (doseq [selected selecteds]
      ;; carefully apply highlighting to children;
      ;; avoids extra calls to highlightAll, which writes warnings to console
      (highlight/highlight-element selected))))

(def ^:private mermaid-loadable (lazy/loadable wiki.bc.mermaid/mount-diagrams!))

(defn- apply-mermaid [rx-theme element !dispose-mermaid]
  (let [child-selector "pre code.mermaid"
        selecteds (-> element
                      (.querySelectorAll child-selector)
                      (js/Array.from)
                      (array-seq))]
    (when (seq selecteds)
      (-> (lazy/load mermaid-loadable)
          (.then (fn [_]
                   ;; skip if the card unmounted while the module was loading
                   (when (.-isConnected element)
                     (let [mount-diagrams! @mermaid-loadable]
                       (reset! !dispose-mermaid (mount-diagrams! rx-theme selecteds))))))))))

(defn inner-html [rx-theme _s]
  (let [!root-element (r/atom nil)
        !dispose-mermaid (atom nil)]
    (r/create-class
     {:component-did-mount (fn [_this]
                             (apply-highlighting @!root-element)
                             (apply-mermaid rx-theme @!root-element !dispose-mermaid))
      :component-will-unmount (fn [_this]
                                (when-let [dispose-mermaid @!dispose-mermaid]
                                  (dispose-mermaid)))
      :reagent-render (fn [_rx-theme s]
                        [:div {:ref (fn [element] (reset! !root-element element))
                               :dangerouslySetInnerHTML (r/unsafe-html s)}])})))
