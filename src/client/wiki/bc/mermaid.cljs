(ns wiki.bc.mermaid
  (:require ["mermaid" :default mermaid]
            [reagent.core :as r]
            [wiki.bc.theme :as theme]))

(defonce ^:private !render-id (atom 0))

(defn- theme-name [theme-value]
  (if (theme/light-theme? theme-value)
    "default"
    "dark"))

(defn- render-into! [theme-value container source]
  (.initialize mermaid #js {:startOnLoad false :theme (theme-name theme-value)})
  (let [render-id (str "bc-mermaid-" (swap! !render-id inc))]
    (-> (.render mermaid render-id source)
        (.then (fn [result]
                 (.remove (.-classList container) "mermaid-error")
                 (set! (.-innerHTML container) (.-svg result))))
        (.catch (fn [_error]
                  ;; mermaid leaks its temp measuring element on parse failure
                  (when-let [orphan (js/document.getElementById (str "d" render-id))]
                    (.remove orphan))
                  ;; leave the diagram source visible rather than a broken card
                  (set! (.-textContent container) source)
                  (.add (.-classList container) "mermaid-error"))))))

(defn- prepare-element!
  "Swap a `pre > code.mermaid` element for an empty diagram container,
  keeping the source around for (re-)rendering."
  [code-element]
  (let [source (.-textContent code-element)
        pre (.-parentElement code-element)
        container (js/document.createElement "div")]
    (.add (.-classList container) "mermaid-diagram")
    (.replaceWith pre container)
    {:container container
     :source source}))

(defn mount-diagrams!
  "Render each `pre > code.mermaid` element as a diagram, re-rendering
  whenever the theme cursor changes. Returns a dispose fn."
  [rx-theme code-elements]
  (let [prepareds (mapv prepare-element! code-elements)
        track-theme (r/track! (fn []
                                (let [theme-value @rx-theme]
                                  (doseq [{:keys [container source]} prepareds]
                                    (render-into! theme-value container source)))))]
    (fn []
      (r/dispose! track-theme))))
