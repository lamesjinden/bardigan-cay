(ns clj-ts.views.manual-copy-card
  (:require [clj-ts.view :as view]
            [clj-ts.views.inner-html-card :refer [inner-html]]))

(defn manual-copy [card]
  [inner-html
   (str "<div class='manual-copy'>"
        (view/card->html card)
        "</div>")])