(ns wiki.bc.views.transcript
  (:require [reagent.core :as r]
            [wiki.bc.card :as card]
            [wiki.bc.keyboard :as keyboard]
            [wiki.bc.navigation :as nav]
            [wiki.bc.transcript :as transcript]))

(defn navigate-via-link-async! [db e]
  (let [tag (-> e .-target)
        data (.getAttribute tag "data")]
    (nav/<navigate! db data)))

(defn- on-escape-key-up [db]
  (transcript/exit-transcript! db))

(defn- on-key-up [db e]
  ;; note - escape doesn't fire for key-press, only key-up
  (let [key-code (.-keyCode e)]
    (cond
      (= key-code keyboard/key-escape-code)
      (on-escape-key-up db))))

(defn transcript [db db-transcript]
  (let [key-up-listener (partial on-key-up db)]

    (r/create-class
     {:component-did-mount    (fn [] (js/window.addEventListener "keyup" key-up-listener))
      :component-will-unmount (fn [] (js/window.removeEventListener "keyup" key-up-listener))
      :reagent-render         (fn []
                                (into [:div {:class    "transcript"
                                             :on-click (fn [e]
                                                         (.preventDefault e)
                                                         (when (card/has-link-target? e)
                                                           (navigate-via-link-async! db e)))}]
                                      @db-transcript))})))
