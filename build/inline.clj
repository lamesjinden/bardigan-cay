(ns inline
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.zip :as zip]
            [babashka.process :refer [shell]]
            [hickory.core :as hickory]
            [hickory.zip :as hickoryz]
            [hickory.render :as hickoryr])
  (:import java.util.Base64))

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

(defn file-path->bytes [file-path]
  (-> file-path
      (io/file)
      (.toPath)
      (java.nio.file.Files/readAllBytes)))

(defn base64-encode-file [file-path]
  (let [source-bytes (file-path->bytes file-path)
        encoder (Base64/getEncoder)]
    (.encodeToString encoder source-bytes)))

(defn inline-material-symbols-font [css-string css-directory-path]
  (let [material-symbols-path (format "%s/vendor/material-symbols/material-symbols-sharp.woff2" css-directory-path)
        material-symbols-encoded (base64-encode-file material-symbols-path)
        replacement (format "src: url(data:font/woff2;base64,%s)" material-symbols-encoded)
        material-symbols-src-pattern #"src: url\(\".*material-symbols-sharp\.woff2\"\)"
        replaced (s/replace css-string material-symbols-src-pattern replacement)]

    (when (= css-string replaced)
      (throw (Exception. "Font inline failed: Material Symbols")))

    replaced))

(defn inline-roboto-font [css-string css-directory-path]
  (let [roboto-path (format "%s/vendor/roboto/Roboto-Regular.ttf" css-directory-path)
        roboto-encoded (base64-encode-file roboto-path)
        replacement (format "src: url(data:font/ttf;base64,%s)" roboto-encoded)
        material-symbols-src-pattern #"src: local\(\"Roboto\"\), url\(\".*Roboto-Regular\.ttf\"\)"
        replaced (s/replace css-string material-symbols-src-pattern replacement)]

    (when (= css-string replaced)
      (throw (Exception. "Font inline failed: Roboto")))

    replaced))


(defn inline-fonts [css-string css-directory-path]
  (-> css-string
      (inline-material-symbols-font css-directory-path)
      (inline-roboto-font css-directory-path)))

(defn inline-styles [html css-directory-path]
  (let [main-css-file-path (format "%s/main.css" css-directory-path)
        source-css (slurp main-css-file-path)
        inline-font-css (inline-fonts source-css css-directory-path)]
    ; inline fonts into main.css
    (spit main-css-file-path inline-font-css)
    ; minify modified css
    (let [minified-css (-> (shell {:out :string} "npx" "lightningcss" "--minify" main-css-file-path)
                           (:out))
          final-css minified-css
          ; inline css into index.html
          html-zipper (-> html
                          (hickory/parse)
                          (hickory/as-hiccup)
                          (hickoryz/hiccup-zip))
          main-css-loc (->> html-zipper
                            (iter-zip)
                            (doall)
                            (find-first main-css-node?))]
      (-> main-css-loc
          (zip/replace [:style {:id main-css-id} final-css])
          (zip/root)
          (hickoryr/hiccup-to-html)))))

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
        css-directory-path (format "%s/public/css/" working-directory-path)
        html (slurp index-html-path)
        html (inline-styles html css-directory-path)
        html (inline-javascript main-js-path html)]
    (spit index-html-path html)))