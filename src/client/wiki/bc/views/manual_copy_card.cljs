(ns wiki.bc.views.manual-copy-card
  (:require [wiki.bc.view :as view]
            [wiki.bc.views.inner-html-card :refer [inner-html]]))

(defn manual-copy [card]
  [inner-html
   (str "<div class='manual-copy'>"
        (view/card->html card)
        "</div>")])