(ns dev_client
  (:require [shadow.cljs.devtools.api :as shadow]))

"Note that the Cursive REPL when first connected always starts out as a CLJ REPL. You can switch it to CLJS by calling (shadow/repl :your-build-id)"

(comment
  ;; connect to the shadow nrepl session (e.g. via cursive remote repl configuration)
  ;; next, load this namespace
  ;; enter a cljs repl session by eval'ing:
  (shadow/repl :app)
  ;; to quit, entire the following into the repl sessino eval (but not from this file as it's not a cljs namespace):
  :cljs/quit

  ;;
  )