(ns clj-ts.render
  (:require [clojure.pprint :as pp]
            [clojure.zip :as zip]
            [hickory.core :as hickory]
            [hickory.render :as hickoryr]
            [hickory.zip :as hickoryz]
            [markdown.core :as md]
            [clj-ts.common :as common]
            [clj-ts.util :as util]))

(defn raw-db [card-server-state]
  (str "<pre>" (with-out-str (pp/pprint (.raw-db card-server-state))) "</pre>"))

(defn md->html [markdown-str]
  (-> markdown-str
      (common/double-comma-table)
      (md/md-to-html-string)
      #_(common/auto-links)
      (common/double-bracket-links)))

(defn missing-page [page-name]
  (str
   "<div style='color:#990000'>A PAGE CALLED "
   page-name
   " DOES NOT EXIST\n\n\n"
   "Check if the name you typed, or in the link you followed is correct.\n\n"
   "If you would *like* to create a page with this name, simply click the [Edit] button to edit this text. When you save, you will create the page</div>"))

(defn process-card-error [source-type source-data exception]
  (str "Error \n\nType was\n" source-type
       "\nSource was\n" source-data
       "\n\nStack trace\n"
       (util/exception-stack exception)))

(defn iter-zip [zipper]
  (->> zipper
       (iterate zip/next)
       (take-while (complement zip/end?))))

(defn find-first [pred coll]
  (some (fn [x]
          (when (pred x)
            x))
        coll))

(def init-js-node-id "init-js")

(defn script-with-id? [node-id loc]
  (let [node (zip/node loc)]
    (when (vector? node)
      (let [[tag {id :id :as _attrs}] node]
        (and
         (= :script tag)
         (= node-id id))))))

(defn find-init-loc [html-doc]
  (let [html-zipper (-> html-doc
                        (hickory/parse)
                        (hickory/as-hiccup)
                        (hickoryz/hiccup-zip))
        init-loc (->> html-zipper
                      (iter-zip)
                      (find-first (partial script-with-id? init-js-node-id)))]
    init-loc))

(defn loc->html-string [loc]
  (-> loc
      (zip/root)
      (hickoryr/hiccup-to-html)))
