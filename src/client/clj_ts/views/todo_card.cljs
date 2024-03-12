(ns clj-ts.views.todo-card
  (:require
   [clojure.string :as s]
   [cljs.pprint :as pprint]))

(defn todo [db card]
  (prn card)
  (let [source-data (get card "source_data")
        ;; code (s/replace (with-out-str (cljs.pprint/pprint source-data)) #"\\n" "\n")
        code source-data]
    (prn code)
    [:div {:style {:white-space "pre-wrap"}} code]))