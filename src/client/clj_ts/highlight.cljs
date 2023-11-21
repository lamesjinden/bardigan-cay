(ns clj-ts.highlight
  (:require ["highlight.js/lib/core" :default hljs]
            ["highlight.js/lib/languages/bash" :as bash]
            ["highlight.js/lib/languages/clojure" :as clojure]
            ["highlight.js/lib/languages/markdown" :as markdown]))

(.registerLanguage hljs "markdown" markdown)
(.registerLanguage hljs "bash" bash)
(.registerLanguage hljs "clojure" clojure)


(defn highlight-all []
  (.highlightAll hljs))
