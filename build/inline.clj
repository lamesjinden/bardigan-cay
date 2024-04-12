(ns inline
  (:require [clojure.zip :as zip]
            [babashka.process :refer [shell]]
            [hickory.core :as hickory]
            [hickory.zip :as hickoryz]
            [hickory.render :as hickoryr]))

(defn iter-zip [zipper]
  (->> zipper
       (iterate zip/next)
       (take-while (complement zip/end?))))

(defn find-first [pred coll]
  (some (fn [x]
          (when (pred x)
            x))
        coll))

(def main-css-id "main-css")

(defn main-css-node? [loc]
  (let [node (zip/node loc)]
    (when (vector? node)
      (let [[tag {id :id :as _attrs}] node]
        (and
         (= :link tag)
         (= main-css-id id))))))

(defn inline-styles [html]
  (let [html-zipper (-> html
                        (hickory/parse)
                        (hickory/as-hiccup)
                        (hickoryz/hiccup-zip))
        main-css-loc (->> html-zipper
                          (iter-zip)
                          (doall)
                          (find-first main-css-node?))
        minified-css (-> (shell {:out :string} "npx" "lightningcss" "--minify" "./resources/public/css/main.css")
                         (:out))]
    (-> main-css-loc
        (zip/replace [:style {:id main-css-id} minified-css])
        (zip/root)
        (hickoryr/hiccup-to-html))))

(def main-js-id "main-js")

(defn main-js-node? [loc]
  (let [node (zip/node loc)]
    (when (vector? node)
      (let [[tag {id :id :as _attrs}] node]
        (and
         (= :script tag)
         (= main-js-id id))))))

(defn inline-javascript [main-js-path html]
  (let [html-zipper (-> html
                        (hickory/parse)
                        (hickory/as-hiccup)
                        (hickoryz/hiccup-zip))
        main-js-loc (->> html-zipper
                         (iter-zip)
                         (doall)
                         (find-first main-js-node?))
        source-js (slurp main-js-path)]
    (-> main-js-loc
        (zip/replace [:script {:id main-js-id} source-js])
        (zip/root)
        (hickoryr/hiccup-to-html))))

(defn inline-assets [working-directory-path]
  (let [index-html-path (format "%s/public/index.html" working-directory-path)
        main-js-path (format "%s/public/js/main.js" working-directory-path)
        html (slurp index-html-path)
        html (inline-styles html)
        html (inline-javascript main-js-path html)]
    (spit index-html-path html)))