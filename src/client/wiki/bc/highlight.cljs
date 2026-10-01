(ns wiki.bc.highlight
  (:require ["highlight.js/lib/core" :default hljs]
            ["highlight.js/lib/languages/bash" :as bash]
            ["highlight.js/lib/languages/clojure" :as clojure]
            ["highlight.js/lib/languages/csharp" :as csharp]
            ["highlight.js/lib/languages/markdown" :as markdown]))

(.registerLanguage hljs "markdown" markdown)
(.registerLanguage hljs "bash" bash)
(.registerLanguage hljs "clojure" clojure)
(.registerLanguage hljs "csharp" csharp)

(defn highlight-all []
  (.highlightAll hljs))

(defn highlight-markdown
  "text as markdown-highlighted html (text is escaped, so the result is
   safe to set as inner html)."
  [text]
  (.-value (.highlight hljs text #js {:language "markdown"})))

(defn highlight-element [element]
  (.highlightElement hljs element))
