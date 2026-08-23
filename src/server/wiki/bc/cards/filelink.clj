(ns wiki.bc.cards.filelink
  (:require [wiki.bc.cards.parsing :as parsing]))

(defn render-html [card-map]
  (let [data (parsing/card-map->card-data card-map)
        {:keys [file-name label]} data]
    (str "<a href='" "/media/" file-name "'>"
         (if label label file-name)
         "</a>")))