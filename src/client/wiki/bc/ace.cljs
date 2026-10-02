(ns wiki.bc.ace
  (:require [clojure.string :as s]
            [cljs.core.async :as a]
            ["ace-builds/src-min-noconflict/ace" :default ace]
            ["ace-builds/src-min-noconflict/ext-language_tools"]
            ["ace-builds/src-min-noconflict/ext-searchbox"]
            ["ace-builds/src-min-noconflict/mode-clojure" :as mode-clojure]
            ["ace-builds/src-min-noconflict/mode-markdown" :as mode-markdown]
            ["ace-builds/src-min-noconflict/theme-cloud9_day"]
            ["ace-builds/src-min-noconflict/theme-cloud9_night"]
            ["ace-builds/src-min-noconflict/theme-tomorrow_night_eighties"]
            [wiki.bc.ace.core :as ace-core]
            [wiki.bc.events.editing :as editing-events]))

(def default-ace-options {:fontSize                 "1.2rem"
                          :minLines                 5
                          :autoScrollEditorIntoView true
                          :enableLiveAutocompletion true})

(def ace-theme ace-core/ace-theme)
(def ace-theme-dark ace-core/ace-theme-dark)
(def ace-theme-synthwave84 ace-core/ace-theme-synthwave84)
(def pick-ace-theme ace-core/pick-ace-theme)
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
     (.setMode ace-session (new mode))
     (ace-core/apply-keymap! ace-instance))))

(defn- disable-list-continuation!
  "Stops the markdown mode from inserting a list marker on the new line when Enter is pressed inside a list;
   the new line only keeps the indentation of the previous one."
  [^js ace-instance]
  (let [^js mode (.getMode (.getSession ace-instance))]
    (set! (.-getNextLineIndent mode)
          (fn [_state line _tab]
            (.$getIndent mode line)))))

(defn set-theme! [^js ace-instance theme]
  (when ace-instance
    (.setTheme ace-instance theme)))

(defn- <change$ [^js ace-instance]
  (let [chan (a/chan (a/sliding-buffer 1))]
    (.on ace-instance "change" (fn [delta]
                                 (a/put! chan delta)))
    chan))

(defn- watch-dirty!
  "Tracks whether the editor differs from its last saved state. Calls on-edit-begin when the content
   first differs from the baseline (initially source-data). Each value on saved$ is the content that
   was just saved: it becomes the new baseline and on-edit-saved is called if the editor was dirty
   (on-edit-begin follows at once if the content has already moved on). Ends when saved$ closes."
  [^js ace-instance source-data saved$ on-edit-begin on-edit-saved]
  (let [changes$ (<change$ ace-instance)
        dirty? (fn [baseline]
                 (not (= baseline (.getValue ace-instance))))]
    (a/go-loop [baseline source-data
                dirty false]
      (let [[value channel] (a/alts! [saved$ changes$])]
        (cond
          (and (= channel saved$) (nil? value))
          nil

          (= channel saved$)
          (let [still-dirty (dirty? value)]
            (when dirty
              (on-edit-saved))
            (when still-dirty
              (on-edit-begin))
            (recur value still-dirty))

          dirty
          (recur baseline dirty)

          :else
          (let [now-dirty (dirty? baseline)]
            (when now-dirty
              (on-edit-begin))
            (recur baseline now-dirty)))))))

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

(defn- <setup-editor [db-theme source-data editor-element edit-box-container saved$ on-edit-begin on-edit-saved]
  (ace-core/<defer (fn []
                     (let [ace-instance (create-edit editor-element)]

                ;; configure ace
                       (let [ace-options (assoc default-ace-options :maxLines "Infinity")
                             theme (pick-ace-theme db-theme)]
                         (configure-ace-instance! ace-instance ace-mode-markdown theme ace-options)
                         (disable-list-continuation! ace-instance))

                ;; watch for unsaved changes; notify app
                       (watch-dirty! ace-instance source-data saved$ on-edit-begin on-edit-saved)

                ;; after ace is visible
                       (a/go
                         (when-some [mutation (a/<! (<css-class-change$ edit-box-container))]
                           (focus-editor-on-mutation ace-instance edit-box-container mutation)))

                       ace-instance))))

(defn <setup-global-editor
  "saved$ carries the saved content each time the page is saved while the editor stays open;
   close it when the editor goes away."
  [db-theme source-data saved$ editor-element edit-box-container]
  (<setup-editor db-theme source-data editor-element edit-box-container
                 saved$
                 editing-events/notify-global-editing-start
                 editing-events/notify-global-editing-end))

(defn <setup-card-editor
  "A card editor closes on save, so nothing is put on saved$; close it when the editor goes away."
  [db-theme source-data hash saved$ editor-element edit-box-container]
  (<setup-editor db-theme source-data editor-element edit-box-container
                 saved$
                 (partial editing-events/notify-editing-begin hash)
                 identity))
