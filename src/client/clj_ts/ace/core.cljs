(ns clj-ts.ace.core
  "Shared Ace editor utilities for use by both the :main and :ace modules.

   This namespace exists because clj-ts.views.nav-bar (in :main) requires Ace
   functionality for the quake console, but cannot depend on clj-ts.ace (in :ace)
   since :ace already depends on :main. Placing shared code here avoids circular
   module dependencies while eliminating duplication."
  (:require [cljs.core.async :as a]
            [clj-ts.theme :as theme]))

(def ace-theme "ace/theme/cloud9_day")
(def ace-theme-dark "ace/theme/cloud9_night")
(def ace-theme-synthwave84 "ace/theme/tomorrow_night_eighties")

(defn pick-ace-theme [db-or-theme]
  (cond
    (theme/synthwave84-theme? db-or-theme) ace-theme-synthwave84
    (theme/light-theme? db-or-theme) ace-theme
    :else ace-theme-dark))

(defn <defer
  "Executes `callback` via 'post message trick'; i.e. Posts a message to a MessageChannel via requestAnimationFrame,
   callback is executed by the MessageChannel callback.

   Returns a channel that contains the result of the callback. If the result is `nil`, the channel will contain `:nil`."
  [callback]
  (let [chan (a/promise-chan)
        channel (js/MessageChannel.)
        port1 (.-port1 channel)
        port2 (.-port2 channel)]
    (set! (.-onmessage port1)
          (fn []
            (let [callback-result (callback)]
              (a/put! chan (if (nil? callback-result) :nil callback-result)))))
    (js/requestAnimationFrame (fn [] (.postMessage port2 js/undefined)))
    chan))
