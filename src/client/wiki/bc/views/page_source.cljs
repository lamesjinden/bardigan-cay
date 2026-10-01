(ns wiki.bc.views.page-source
  "The read-only source view of a snapshot: the page's text as committed
   at the revision, highlighted as markdown, with a copy button."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [wiki.bc.highlight :as highlight]))

(def ^:private copied-feedback-ms 1500)

;; absent outside a secure context (plain http, other than localhost)
(defn- clipboard []
  (.-clipboard js/navigator))

(defn- copy-button [_db-raw]
  (let [copied? (r/atom false)]
    (fn [db-raw]
      [:button.big-btn.page-source-copy
       {:title    "Copy source"
        :on-click (fn []
                    (-> (.writeText (clipboard) @db-raw)
                        (.then (fn [_]
                                 (reset! copied? true)
                                 (js/setTimeout (fn [] (reset! copied? false)) copied-feedback-ms)))
                        (.catch (fn [error] (js/console.error "Failed to copy text:" error)))))}
       [:span {:class [:material-symbols-sharp :clickable]}
        (if @copied?
          "check"
          "content_copy")]])))

(defn page-source [db-raw]
  (let [raw @db-raw]
    [:div.page-source
     (if (str/blank? raw)
       [:div.page-source-status "The page has no source at this revision."]
       [:<>
        (when (clipboard)
          [copy-button db-raw])
        [:pre.page-source-text
         [:code.hljs.language-markdown
          {:dangerouslySetInnerHTML (r/unsafe-html (highlight/highlight-markdown raw))}]]])]))
