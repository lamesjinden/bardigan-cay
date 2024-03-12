(ns clj-ts.views.transcript
  (:require [reagent.core :as r]
            [clj-ts.card :as card]
            [clj-ts.keyboard :as keyboard]
            [clj-ts.navigation :as nav]
            [clj-ts.transcript]))

(defn navigate-via-link-async! [db e]
  (let [tag (-> e .-target)
        data (.getAttribute tag "data")]
    (nav/<navigate! db data)))

(defn- on-escape-key-up [db]
  (clj-ts.transcript/exit-transcript! db))

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
                                [:div {:class                   "transcript"
                                       :dangerouslySetInnerHTML {:__html @db-transcript}
                                       :on-click                (fn [e]
                                                                  (.preventDefault e)
                                                                  (when (card/has-link-target? e)
                                                                    (navigate-via-link-async! db e)))}])})))
