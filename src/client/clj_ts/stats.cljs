(ns clj-ts.stats
  (:require [kixi.stats.digest :as kixi-digest]
            [kixi.stats.estimate :as kixi-est]))

(defn linear-regression [xs ys]
  (let [sum-squares (transduce identity (kixi-digest/sum-squares first last) (map vector xs ys))
        model (kixi-est/simple-linear-regression sum-squares)
        offset (.-offset model)
        slope (.-slope model)]
    (mapv (fn [x]
            [x (+ (* x slope) offset)])
          xs)))

(defn xy-pairs->map
  "
   Converts a sequence of [x y] pairs into a map that can be passed to Plotly.
   The resulting map has an :x whose value is a vector of all x points
                     and an :y whose value is a vector of all y points.
   A seed map maybe provided; `update`s will be applied to the seed value.
   "
  ([seed pairs]
   (reduce (fn [acc [x y]]
             (-> acc
                 (update :x conj x)
                 (update :y conj y)))
           seed
           pairs))
  ([pairs]
   (xy-pairs->map {:x [] :y []} pairs)))
