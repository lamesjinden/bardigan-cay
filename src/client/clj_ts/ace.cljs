(ns clj-ts.ace
  (:require ["ace-builds/src-min-noconflict/ace" :default ace]
            ["ace-builds/src-min-noconflict/theme-cloud9_day"]
            ["ace-builds/src-min-noconflict/theme-cloud9_night"]
            ["ace-builds/src-min-noconflict/mode-clojure" :as mode-clojure]
            ["ace-builds/src-min-noconflict/mode-markdown" :as mode-markdown]))

(def default-ace-options {:fontSize                 "1.2rem"
                          :minLines                 5
                          :autoScrollEditorIntoView true})

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