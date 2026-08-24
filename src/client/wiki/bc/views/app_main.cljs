(ns wiki.bc.views.app-main
  (:require [reagent.core :as r]
            [wiki.bc.views.card-list :refer [card-list]]
            [wiki.bc.views.jobs :refer [jobs-view]]
            [wiki.bc.views.lazy-editor :refer [suspended-editor-component]]
            [wiki.bc.views.transcript :refer [transcript]]
            [wiki.bc.networks :refer [network-canvas]]))

(defn app-main [db]
  (let [mode (:mode @db)
        rx-raw (r/cursor db [:raw])
        rx-transcript (r/cursor db [:transcript])
        rx-jobs (r/cursor db [:jobs])
        rx-cards (r/cursor db [:cards])
        rx-system-cards (r/cursor db [:system-cards])]
    [:main
     (condp = mode

       :editing
       (suspended-editor-component {:db db :db-raw rx-raw})

       :viewing
       [card-list db rx-cards rx-system-cards]

       :transcript
       [transcript db rx-transcript]

       :jobs
       [jobs-view db rx-jobs]

       :network-editor
       [network-canvas])]))