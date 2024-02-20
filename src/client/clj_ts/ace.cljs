(ns clj-ts.ace
  (:require [clojure.string :as s]
            [cljs.core.async :as a]
            ["ace-builds/src-min-noconflict/ace" :default ace]
            ["ace-builds/src-min-noconflict/ext-language_tools"]
            ["ace-builds/src-min-noconflict/ext-searchbox"]
            ["ace-builds/src-min-noconflict/mode-clojure" :as mode-clojure]
            ["ace-builds/src-min-noconflict/mode-markdown" :as mode-markdown]
            ["ace-builds/src-min-noconflict/theme-cloud9_day"]
            ["ace-builds/src-min-noconflict/theme-cloud9_night"]
            [clj-ts.events.editing :as editing-events]
            [clj-ts.theme :as theme]))

(def default-ace-options {:fontSize                 "1.2rem"
                          :minLines                 5
                          :autoScrollEditorIntoView true
                          :enableLiveAutocompletion true})

(def ace-theme "ace/theme/cloud9_day")
(def ace-theme-dark "ace/theme/cloud9_night")
(def ace-mode-clojure (.-Mode mode-clojure))
(def ace-mode-markdown (.-Mode mode-markdown))

(defn create-edit [editor-element]
  (.edit ace editor-element))

(defn configure-ace-instance!
  ([ace-instance mode]
   (configure-ace-instance! ace-instance mode ace-theme default-ace-options))
  ([^js ace-instance mode theme options]
   (let [^js ace-session (.getSession ace-instance)]
     (.setTheme ace-instance theme)
     (.setOptions ace-instance (clj->js options))
     (.setShowInvisibles ace-instance false)
     (.setMode ace-session (new mode)))))

(defn set-theme! [^js ace-instance theme]
  (when ace-instance
    (.setTheme ace-instance theme)))

(defn- <defer
  "executes `callback` via 'post message trick'; i.e. Posts a message to a MessageChannel via requestAnimationFrame, 
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

(defn- <editor-dirty$ [ace-instance original-state]
  (let [chan (a/promise-chan)]
    (.on ace-instance "change" (fn when-changed [delta]
                                 (when (not (= original-state (.getValue ace-instance)))
                                   (a/put! chan delta)
                                   (.off ace-instance "change" when-changed))))
    chan))

(defn- <css-class-change$ [target-node]
  (let [chan (a/chan)
        config #js {"attributeFilter" ["class"] "attributeOldValue" true}
        callback (fn [mutation-list observer]
                   (a/put! chan {:mutation-list mutation-list :observer observer}))
        observer (js/MutationObserver. callback)]
    (.observe observer target-node config)
    chan))

(defn- focus-editor-on-mutation [ace-instance edit-box-container {:keys [mutation-list observer] :as _result}]
  (when-let [mutation-record (some #(and (= "class" (.-attributeName %)) %) (array-seq mutation-list))]
    (let [configured-class-name "configured"
          old-value (.-oldValue mutation-record)
          current-value (.-className edit-box-container)]
      (when (and (not (s/includes? old-value configured-class-name)) (s/includes? current-value configured-class-name))
        (.focus ace-instance)
        (.moveCursorToPosition ace-instance #js {"row" 0 "column" 0})
        (.scrollIntoView edit-box-container)
        (.disconnect observer)))))

(defn <setup-global-editor [db-theme source-data editor-element edit-box-container]
  (<defer   (fn []
              (let [ace-instance (create-edit editor-element)]

                ;; configure ace
                (let [ace-options (assoc default-ace-options :maxLines "Infinity")
                      theme       (if (theme/light-theme? db-theme)
                                    ace-theme
                                    ace-theme-dark)]
                  (configure-ace-instance! ace-instance ace-mode-markdown theme ace-options))

                ;; watch for the first change; notify app
                (a/go
                  (when-some [_delta (a/<! (<editor-dirty$ ace-instance source-data))]
                    (editing-events/notify-global-editing-start)))

                ;; after ace is visible
                (a/go
                  (when-some [mutation (a/<! (<css-class-change$ edit-box-container))]
                    (focus-editor-on-mutation ace-instance edit-box-container mutation)))

                ace-instance))))

(defn <setup-card-editor [db-theme source-data hash editor-element edit-box-container]
  (<defer (fn []
            (let [ace-instance (create-edit editor-element)]

              ;; configure ace
              (let [ace-options (assoc default-ace-options :maxLines "Infinity")
                    theme (if (theme/light-theme? db-theme)
                            ace-theme
                            ace-theme-dark)]
                (configure-ace-instance! ace-instance ace-mode-markdown theme ace-options))

              ;; watch for the first change; notify app
              (a/go
                (when-some [_delta (a/<! (<editor-dirty$ ace-instance source-data))]
                  (editing-events/notify-editing-begin hash)))

              ;; after ace is visible
              (a/go
                (when-some [mutation (a/<! (<css-class-change$ edit-box-container))]
                  (focus-editor-on-mutation ace-instance edit-box-container mutation)))

              ;; return the ace-instance
              ace-instance))))