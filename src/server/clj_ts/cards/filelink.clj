(ns clj-ts.cards.filelink
  (:require [clj-ts.render :as render]))

(defn render-html [card-map]
  (let [data (render/card-map->card-data card-map)
        {:keys [file-name label]} data]
    (str "<a href='" "/media/" file-name "'>"
         (if label label file-name)
         "</a>")))