(ns clj-ts.views.graph
  (:require [reagent.core :as r]
            ["react-plotly.js$default" :as plotly]
            [clj-ts.card :as cards]))

(defn- ->fgcolor []
  (let [document-element (.-documentElement js/document)
        computed-styles (js/getComputedStyle document-element)
        foreground-color (.getPropertyValue computed-styles "--primary-foreground-color")]
    foreground-color))

(def default-layout {:autosize true
                     :paper_bgcolor "transparent"
                     :plot_bgcolor "transparent"
                     :font {:color (->fgcolor)}})

(defn graph
  ([data layout]
   (let [layout (merge layout default-layout)]
     [:div.graph-root
      [(r/adapt-react-class plotly) {:data data
                                     :class-name "plotly-container"
                                     :layout layout
                                     :use-resize-handler true
                                     :config {:display-mode-bar false}}]]))
  ([data]
   (graph data default-layout)))

(defn graph-card [_db card]
  (let [card-configuration (or (cards/->card-configuration card) {})
        data (or (:data card-configuration) [])
        layout (or (:layout card-configuration) {})]
    [graph data layout]))

(defn graph-card-component [{:keys [db card] :as _args}]
  (graph-card db (js->clj card)))