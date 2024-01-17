(ns clj-ts.network)

(defn calculate-node-size [label {:keys [font-size padding]}]
  (let [text-width (* (count label) font-size 0.6)          ; Assuming each character has a width of 0.6 * font-size
        rect-width (+ text-width (* 2 padding))
        rect-height 40]
    [rect-width rect-height]))

(defn node->svg [node style-map]
  (let [[id label x y] node
        font-size (:font-size style-map)
        font-family (:font-family style-map)
        text-anchor (:text-anchor style-map)
        text-y-offset (/ font-size 2)
        [rect-width rect-height] (calculate-node-size label style-map)]
    [:g {:key id}
     [:rect {:x      (- x (/ rect-width 2)) :y (- y (/ rect-height 2))
             :width  rect-width :height rect-height
             :stroke "black" :fill "white"}]
     [:text {:x         x :y (+ y text-y-offset) :font-family font-family
             :font-size font-size :text-anchor text-anchor
             :class     "wikilink" :data label}
      label]]))

(defn line-rect-intersect [x1 y1 x2 y2 w h]
  (let [dx (- x2 x1)
        dy (- y2 y1)
        half-w (/ w 2)
        half-h (/ h 2)
        left (- x2 half-w)
        right (+ x2 half-w)
        top (- y2 half-h)
        bottom (+ y2 half-h)
        t-min-x (if (not= dx 0) (/ (- left x1) dx) Double/POSITIVE_INFINITY)
        t-max-x (if (not= dx 0) (/ (- right x1) dx) Double/NEGATIVE_INFINITY)
        t-min-y (if (not= dy 0) (/ (- top y1) dy) Double/POSITIVE_INFINITY)
        t-max-y (if (not= dy 0) (/ (- bottom y1) dy) Double/NEGATIVE_INFINITY)
        t-enter (max (min t-min-x t-max-x) (min t-min-y t-max-y))]
    [(+ x1 (* t-enter dx)) (+ y1 (* t-enter dy))]))

(defn arc->svg [arc nodes style-map]
  (let [[n1 n2] arc
        node1 (some #(when (= (first %) n1) %) nodes)
        node2 (some #(when (= (first %) n2) %) nodes)
        [x1 y1] [(nth node1 2) (nth node1 3)]
        [x2 y2] [(nth node2 2) (nth node2 3)]
        [w1 h1] (calculate-node-size (nth node1 1) style-map)
        [w2 h2] (calculate-node-size (nth node2 1) style-map)
        [src-x src-y] (line-rect-intersect x2 y2 x1 y1 w1 h1) ; Reversed the origin and destination nodes
        [dest-x dest-y] (line-rect-intersect x1 y1 x2 y2 w2 h2)]
    [:line {:x1     src-x :y1 src-y :x2 dest-x :y2 dest-y
            :stroke "black" :stroke-width 2 :marker-end "url(#arrow)"}]))

(defn network->svg [network]
  (let [style-map {:font-size   20
                   :font-family "Arial"
                   :text-anchor "middle"
                   :padding     10}]
    [:svg {:width "100%" :height "100%" :viewBox "0 0 500 500"}
     [:defs
      [:marker {:id     "arrow" :markerWidth 10 :markerHeight 10 :refX 9 :refY 3
                :orient "auto" :markerUnits "strokeWidth"}
       [:path {:d "M0,0 L0,6 L9,3 z" :fill "black"}]]]
     (map #(node->svg % style-map) (network :nodes))
     (map #(arc->svg % (network :nodes) style-map) (network :arcs))]))
