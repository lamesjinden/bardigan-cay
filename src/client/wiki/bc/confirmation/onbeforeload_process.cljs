(ns wiki.bc.confirmation.onbeforeload-process
  (:require [cljs.core.async :as a]))

(defn- update-edit-sessions [editing {:keys [id action]}]
  (condp = action
    :start (conj editing id)
    :end (disj editing id)
    editing))

(defn <create-onbeforeload-process [editing$]
  (a/go-loop [editing #{}]
             (when-some [value (a/<! editing$)]
               (let [editing' (update-edit-sessions editing value)]
                 (if (empty? editing')
                   (set! (.-onbeforeunload js/window) nil)
                   (set! (.-onbeforeunload js/window) (fn [] true)))
                 (recur editing')))))
