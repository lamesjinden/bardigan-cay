(ns clj-ts.views.app-main
  (:require [reagent.core :as r]
            [clj-ts.views.editor :refer [editor]]
            [clj-ts.views.card-list :refer [card-list]]
            [clj-ts.views.transcript :refer [transcript]]
            [clj-ts.networks :refer [network-canvas]]))

(defn app-main [db]
  (let [mode (:mode @db)
        rx-raw (r/cursor db [:raw])
        rx-transcript (r/cursor db [:transcript])
        rx-cards (r/cursor db [:cards])
        rx-system-cards (r/cursor db [:system-cards])]
    [:main
     (condp = mode

       :editing
       [editor db rx-raw]

       :viewing
       [card-list db rx-cards rx-system-cards]

       :transcript
       [transcript db rx-transcript]

       :network-editor
       [network-canvas])]))