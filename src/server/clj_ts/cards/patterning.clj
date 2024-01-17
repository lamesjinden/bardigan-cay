(ns clj-ts.cards.patterning
  (:require [patterning.view :as p-views]
            [sci.core :as sci]
            [clj-ts.patterning])
  (:import (java.io PrintWriter StringWriter)))

(defn one-pattern
  "Evaluate one pattern"
  [data]
  (try
    (let [pattern (sci/eval-string data {:namespaces clj-ts.patterning/patterning-ns})
          svg (p-views/make-svg 500 500 pattern)]
      (str
        "<div>"
        svg
        "</div>
 </div>
 " "
 <div class='calculated-out'>
<pre>"
        data
        "</pre></div>"))
    (catch Exception e
      (let [sw (new StringWriter)
            pw (new PrintWriter sw)]
        (.printStackTrace e pw)
        (println e)
        (str "<div class='calculated-out'><pre>
Exception :: " (.getMessage e) "

" (-> sw .toString) "</pre></div>")))))
