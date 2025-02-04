(ns clj-ts.view
  (:require [clojure.string :as str]
            [markdown.core :as md]
            [clj-ts.common :refer [double-comma-table
                                   double-bracket-links
                                   auto-links]]))

(defn string->html [s]
  (-> s
      (double-comma-table)
      (md/md->html)
      #_(auto-links)
      (double-bracket-links)))

(defn card->html [card]
  (-> (get card "server_prepared_data")
      (string->html)))

(defn not-blank? [card]
  (not= "" (str/trim (get card "source_data"))))

(defn send-to-clipboard [s]
  (-> (js/Promise.resolve (.writeText js/navigator.clipboard s))
      (.then (fn [_] (js/console.log (str "Text copied to clipboard " s))))
      (.catch (fn [error] (js/console.error "Failed to copy text:", error)))))

(defn ->display
  ([x display]
   (if x display :none))
  ([x] (->display x :block)))