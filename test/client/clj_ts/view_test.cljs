(ns clj-ts.view-test
  (:require [cljs.test :refer (deftest is)]
            [clojure.string :as str]
            [clj-ts.view :as view]))

(deftest string->html_renders-standard-markdown-table
  (let [html (view/string->html "| A | B |\n|-------|-------|\n| 1 | 2 |")]
    (is (str/includes? html "<table>"))
    (is (str/includes? html "<th>A</th>"))
    (is (str/includes? html "<td>1</td>"))))

(deftest string->html_renders-double-comma-table
  (let [html (view/string->html "a,,b\nc,,d")]
    (is (str/includes? html "double-comma-table"))
    (is (str/includes? html "<td>a</td>"))
    (is (str/includes? html "<td>d</td>"))))
