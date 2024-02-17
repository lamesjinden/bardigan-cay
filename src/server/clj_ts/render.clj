(ns clj-ts.render
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [markdown.core :as md]
            [clj-ts.common :as common]
            [clj-ts.util :as util]))

(defn raw-db [card-server-state]
  (str "<pre>" (with-out-str (pp/pprint (.raw-db card-server-state))) "</pre>"))

(defn md->html [markdown-str]
  (-> markdown-str
      (common/double-comma-table)
      (md/md-to-html-string)
      (common/auto-links)
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

(defn card-map->card-data [card-map]
  (let [{[a b :as _tokens] :tokens} card-map
        card-data (if-let [readable-token (when (= :keyword (:type a)) (:value b))]
                    (edn/read-string readable-token)
                    (:value a))]
    card-data))