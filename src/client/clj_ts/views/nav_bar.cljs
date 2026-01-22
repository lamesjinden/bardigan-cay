(ns clj-ts.views.nav-bar
  (:require [cljs.core.async :as a]
            [clojure.string :as str]
            [reagent.core :as r]
            [sci.core :as sci]
            ["ace-builds/src-min-noconflict/ace" :default ace]
            ["ace-builds/src-min-noconflict/ext-language_tools"]
            ["ace-builds/src-min-noconflict/mode-clojure" :as mode-clojure]
            ["ace-builds/src-min-noconflict/theme-cloud9_day"]
            ["ace-builds/src-min-noconflict/theme-cloud9_night"]
            [clj-ts.ace.core :as ace-core]
            [clj-ts.events.transcript :as transcript-events]
            [clj-ts.highlight :as highlight]
            [clj-ts.http :as http]
            [clj-ts.navigation :as nav]
            [clj-ts.theme :as theme]
            [clj-ts.transcript :as transcript]
            [clj-ts.view :as view]
            [clj-ts.views.app-menu :refer [app-menu]]
            [clj-ts.views.autocomplete-input :refer [autocomplete-input]]))

;; region nav input handlers

(defn- nav-on-submit [db _local-db _e input-value]
  (nav/<navigate! db input-value))

(defn- nav-on-clicked [db _local-db _e name]
  (nav/<navigate! db name))

(defn- nav-on-key-up-enter [db _local-db _e name]
  (nav/<navigate! db name))

;; endregion

;; region search

(defn- load-search-results! [db cleaned-query body]
  (let [edn (js->clj body)
        result (get edn "result_text")]
    (transcript/prepend-transcript! db
                                    (str "Searching for " cleaned-query)
                                    (view/string->html result))
    (transcript-events/<notify-transcript-navigating db)))

(defn- search-text-async! [db query-text]
  (let [cleaned-query (-> (or query-text "")
                          (str/replace "\"" "")
                          (str/replace "'" "")
                          (str/trim)
                          (js/encodeURI))]
    (when (not (str/blank? cleaned-query))
      (a/go
        (when-let [result (a/<! (http/<http-get (str "/api/search?q=" cleaned-query)))]
          (let [{body-text :body} result
                body (.parse js/JSON body-text)]
            (load-search-results! db cleaned-query body)))))))

(defn- on-search-clicked [db query-text]
  (let [query-text (-> (or query-text "")
                       (str/trim))]
    (when (not (str/blank? query-text))
      (search-text-async! db query-text))))

(defn- on-navigate-clicked [db input-value]
  (let [input-value (-> (or input-value "")
                        (str/trim))]
    (when (not (str/blank? input-value))
      (nav/<navigate! db input-value))))

;; endregion

;; region eval

(defn- eval-input! [db local-db input-value]
  (let [code input-value]
    (try
      (let [result (sci/eval-string code)]
        (transcript/prepend-transcript! db code result)
        (transcript-events/<notify-transcript-navigating db))
      (catch :default e
        (js/console.error "Eval error:" e)
        (transcript/prepend-transcript! db code (str "Error: " (.-message e)))
        (transcript-events/<notify-transcript-navigating db)))
    ;; Clear both inputs
    (swap! local-db assoc :input-value nil)
    (when-let [editor (:quake-editor @local-db)]
      (.setValue editor "" -1))))

(defn- on-eval-clicked [db local-db input-value]
  (let [current (-> (or input-value "")
                    (str/trim))]
    (when (not (str/blank? current))
      (eval-input! db local-db current))))

(defn- on-link-click [db e target aux-clicked?]
  (.preventDefault e)
  (nav/<on-link-clicked db e target aux-clicked?))

(defn- on-transcript-click [db e]
  (.preventDefault e)
  (transcript-events/<notify-transcript-navigating db))

;; endregion

;; region quake mode

;; REPL special vars
(defonce ^:private sci-var-1 (sci/new-dynamic-var '*1 nil))
(defonce ^:private sci-var-2 (sci/new-dynamic-var '*2 nil))
(defonce ^:private sci-var-3 (sci/new-dynamic-var '*3 nil))
(defonce ^:private sci-var-e (sci/new-dynamic-var '*e nil))

(defonce ^:private quake-sci-ctx
  (sci/init {:namespaces {'user {'*1 sci-var-1
                                 '*2 sci-var-2
                                 '*3 sci-var-3
                                 '*e sci-var-e}}}))

(defn- update-repl-vars! [result]
  (sci/alter-var-root sci-var-3 (constantly @sci-var-2))
  (sci/alter-var-root sci-var-2 (constantly @sci-var-1))
  (sci/alter-var-root sci-var-1 (constantly result)))

(defn- update-repl-error! [error]
  (sci/alter-var-root sci-var-e (constantly error)))

(def ^:private quake-ace-mode-clojure (.-Mode mode-clojure))
(def ^:private quake-ace-options {:fontSize "1.1rem"
                                  :minLines 1
                                  :maxLines 10
                                  :showGutter false
                                  :highlightActiveLine false
                                  :showPrintMargin false
                                  :enableLiveAutocompletion true
                                  :showFoldWidgets false})

;; Commands to remove from quake editor to make it behave like a simple input
(def ^:private quake-disabled-commands
  ["showSettingsMenu"
   "openCommandPalette"
   "openCommandPallete"         ; both spellings exist
   "gotoline"
   "find"
   "findnext"
   "findprevious"
   "replace"
   "replaceall"
   "movelinesup"
   "movelinesdown"
   "copylinesup"
   "copylinesdown"
   "del"                        ; Ctrl+D - delete/duplicate
   "selectMoreBefore"
   "selectMoreAfter"
   "selectOrFindNext"
   "selectOrFindPrevious"
   "splitIntoLines"
   "togglecomment"
   "toggleBlockComment"
   "sortlines"
   "touppercase"
   "tolowercase"
   "foldall"
   "unfoldall"
   "fold"
   "unfold"
   "toggleFoldWidget"
   "toggleParentFoldWidget"
   "foldOther"
   "jumptomatching"
   "expandSnippet"])

(defn- <setup-quake-editor [db-theme editor-element]
  (ace-core/<defer
   (fn []
     (let [ace-instance (.edit ace editor-element)
           ace-theme (if (theme/light-theme? db-theme)
                       ace-core/ace-theme
                       ace-core/ace-theme-dark)
           ^js ace-session (.getSession ace-instance)
           commands (.-commands ace-instance)]
       (.setTheme ace-instance ace-theme)
       (.setOptions ace-instance (clj->js quake-ace-options))
       (.setShowInvisibles ace-instance false)
       (.setMode ace-session (new quake-ace-mode-clojure))
       ;; Remove non-essential commands to make editor behave like a simple input
       (doseq [cmd quake-disabled-commands]
         (.removeCommand commands cmd))
       ;; Override theme background to blend with nav container
       (set! (.. ace-instance -container -style -background) "transparent")
       ace-instance))))

(defn- on-container-click [db-quake-mode? e]
  (when (.-ctrlKey e)
    (.preventDefault e)
    (swap! db-quake-mode? not)))

(defn- scroll-quake-results-to-bottom! [local-db]
  (ace-core/<defer
   (fn []
     (when-let [results-el (:quake-results-ref @local-db)]
       (set! (.-scrollTop results-el) (.-scrollHeight results-el))))))

(defn- quake-eval! [db local-db]
  (when-let [editor (:quake-editor @local-db)]
    (let [code (.getValue editor)
          display-expr (str/trim code)]
      (when (not (str/blank? code))
        (try
          (let [result (sci/eval-string* quake-sci-ctx code)]
            ;; Update REPL vars
            (update-repl-vars! result)
            ;; Add to quake results
            (swap! local-db update :quake-results conj {:expr display-expr :result (pr-str result) :error? false})
            ;; Reset history index
            (swap! local-db assoc :quake-history-index nil)
            ;; Also add to transcript (but don't switch view)
            (transcript/prepend-transcript! db code result)
            ;; Clear both editors
            (.setValue editor "" -1)
            (swap! local-db assoc :input-value nil)
            ;; Scroll results to bottom
            (scroll-quake-results-to-bottom! local-db))
          (catch :default e
            ;; Update *e with the error
            (update-repl-error! e)
            (swap! local-db update :quake-results conj {:expr display-expr :result (str "Error: " (.-message e)) :error? true})
            (swap! local-db assoc :quake-history-index nil)
            (.setValue editor "" -1)))))))

(defn- quake-history-prev! [local-db]
  (when-let [editor (:quake-editor @local-db)]
    (let [results (:quake-results @local-db)
          current-index (:quake-history-index @local-db)
          max-index (dec (count results))]
      (when (>= max-index 0)
        (let [new-index (if (nil? current-index)
                          max-index
                          (max 0 (dec current-index)))
              expr (:expr (nth results new-index))]
          (swap! local-db assoc :quake-history-index new-index)
          (.setValue editor expr -1)
          (.navigateLineEnd editor))))))

(defn- quake-history-next! [local-db]
  (when-let [editor (:quake-editor @local-db)]
    (let [results (:quake-results @local-db)
          current-index (:quake-history-index @local-db)
          max-index (dec (count results))]
      (when (and current-index (>= max-index 0))
        (if (>= current-index max-index)
          ;; At the end, clear the editor
          (do
            (swap! local-db assoc :quake-history-index nil)
            (.setValue editor "" -1))
          ;; Move to next (more recent) entry
          (let [new-index (inc current-index)
                expr (:expr (nth results new-index))]
            (swap! local-db assoc :quake-history-index new-index)
            (.setValue editor expr -1)
            (.navigateLineEnd editor)))))))

(defn- highlighted-expr [expr]
  (let [!element (clojure.core/atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_] (when-let [el @!element] (highlight/highlight-element el)))
      :reagent-render
      (fn [expr]
        [:code.language-clojure.quake-expr {:ref (fn [el] (reset! !element el))} expr])})))

(defn- quake-results-view [local-db results]
  [:div.quake-results {:ref (fn [el] (swap! local-db assoc :quake-results-ref el))}
   (for [[idx {:keys [expr result error?]}] (map-indexed vector results)]
     ^{:key idx}
     [:div.quake-result-item
      [highlighted-expr expr]
      [:span.quake-separator " => "]
      [:span {:class (if error? "quake-result error" "quake-result")} result]])])

(defn- quake-theme-tracker [local-db db]
  (when-let [editor (:quake-editor @local-db)]
    (let [db-theme (:theme @db)
          ace-theme (if (theme/light-theme? db-theme)
                      ace-core/ace-theme
                      ace-core/ace-theme-dark)]
      (.setTheme editor ace-theme)
      ;; Override theme background to blend with nav container
      (set! (.. editor -container -style -background) "transparent"))))

(defn- quake-editor-component [db local-db]
  (let [!editor (clojure.core/atom nil)
        !element (clojure.core/atom nil)
        track-theme (r/track! (partial quake-theme-tracker local-db db))]
    (r/create-class
     {:display-name "quake-editor"
      :component-did-mount
      (fn [_]
        (when-let [el @!element]
          (let [db-theme (:theme @db)]
            (a/go
              (when-let [ace-instance (a/<! (<setup-quake-editor db-theme el))]
                (reset! !editor ace-instance)
                (swap! local-db assoc :quake-editor ace-instance)
                ;; Add keybindings
                (let [commands (.-commands ace-instance)]
                  (.addCommand commands
                               #js {:name "quakeEval"
                                    :bindKey #js {:win "Ctrl-Enter" :mac "Cmd-Enter"}
                                    :exec (fn [_] (quake-eval! db local-db))})
                  (.addCommand commands
                               #js {:name "historyPrev"
                                    :bindKey #js {:win "Ctrl-Up" :mac "Ctrl-Up"}
                                    :exec (fn [_] (quake-history-prev! local-db))})
                  (.addCommand commands
                               #js {:name "historyNext"
                                    :bindKey #js {:win "Ctrl-Down" :mac "Ctrl-Down"}
                                    :exec (fn [_] (quake-history-next! local-db))}))
                ;; Populate with input from normal mode if present
                (when-let [input-value (:input-value @local-db)]
                  (.setValue ace-instance input-value -1)
                  (.navigateLineEnd ace-instance))
                (.focus ace-instance)
                ;; Scroll results to bottom when entering quake mode
                (scroll-quake-results-to-bottom! local-db))))))
      :component-will-unmount
      (fn [_]
        (swap! local-db assoc :quake-editor nil)
        (r/dispose! track-theme)
        (when-let [editor @!editor]
          (.destroy editor)))
      :reagent-render
      (fn [_db _local-db]
        [:div.quake-input-container {:ref (fn [el] (reset! !element el))}])})))

(defn- quake-results-panel [local-db]
  [:div.quake-results-panel
   [quake-results-view local-db (:quake-results @local-db)]])

;; endregion

(defn nav-bar [_db _db-nav-links _db-quake-mode?]
  (let [local-db (r/atom {:input-value nil
                          :suggestions []
                          :autocomplete-visible? false
                          :quake-results []
                          :quake-editor nil
                          :quake-history-index nil})
        !quake-mode-cursor (clojure.core/atom nil)
        global-keydown-handler (fn [e]
                                 (when (and (.-ctrlKey e)
                                            (= (.-code e) "Backquote"))
                                   (.preventDefault e)
                                   (when-let [cursor @!quake-mode-cursor]
                                     (swap! cursor not))))]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (.addEventListener js/document "keydown" global-keydown-handler))
      :component-will-unmount
      (fn [_]
        (.removeEventListener js/document "keydown" global-keydown-handler))
      :reagent-render
      (fn [db db-nav-links db-quake-mode?]
        (reset! !quake-mode-cursor db-quake-mode?)
        (let [nav-links @db-nav-links
              quake-mode? @db-quake-mode?]
          [:div.nav-container {:class (when quake-mode? "quake-mode")
                               :on-click (fn [e] (on-container-click db-quake-mode? e))}
           [:nav#header-nav
            (->> nav-links
                 (remove #(= % "Transcript"))
                 (mapcat #(vector [:a.clickable {:key          %
                                                 :on-click     (fn [e] (on-link-click db e % false))
                                                 :on-aux-click (fn [e] (on-link-click db e % true))
                                                 :href         (str "/pages/" %)} %])))
            [:a.clickable {:key "transcript"
                           :on-click (fn [e] (on-transcript-click db e))} "Transcript"]
            [app-menu db (r/cursor db [:theme])]]

           (when quake-mode?
             [quake-results-panel local-db])

           [:div#header-input
            (if quake-mode?
              [quake-editor-component db local-db]
              [autocomplete-input {:db db
                                   :local-db local-db
                                   :placeholder "Navigate, Search, or Eval"
                                   :on-submit nav-on-submit
                                   :on-clicked nav-on-clicked
                                   :on-key-up-enter nav-on-key-up-enter
                                   :class-name "nav-input-text"
                                   :container-class "nav-input-container"
                                   :container-selector "#header-input"}])
            [:div.header-input-actions
             (when (and (not quake-mode?) (not (nil? (:input-value @local-db))))
               [:button#close-button.header-input-button
                {:on-click (fn [] (swap! local-db assoc :input-value nil))}
                [:span {:class [:material-symbols-sharp :clickable]} "close"]])
             (when (and (not quake-mode?) (not (nil? (:input-value @local-db))))
               [:div.header-input-separator])
             (when (not quake-mode?)
               [:button#go-button.header-input-button
                {:on-click (fn [] (on-navigate-clicked db (:input-value @local-db)))}
                [:span {:class [:material-symbols-sharp :clickable]} "navigate_next"]])
             (when (not quake-mode?)
               [:button.header-input-button
                {:on-click (fn [] (on-search-clicked db (:input-value @local-db)))}
                [:span {:class [:material-symbols-sharp :clickable]} "search"]])
             [:button#lambda-button.header-input-button
              {:class (when quake-mode? "quake-active")
               :on-click (fn []
                           (if quake-mode?
                             (quake-eval! db local-db)
                             (on-eval-clicked db local-db (:input-value @local-db))))}
              [:span {:class [:material-symbols-sharp :clickable]} "λ"]]
             (when quake-mode?
               [:button.header-input-button
                {:on-click (fn [] (swap! local-db assoc :quake-results [] :quake-history-index nil))}
                [:span {:class [:material-symbols-sharp :clickable]} "clear_all"]])]]]))})))
