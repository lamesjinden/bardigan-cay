(ns clj-ts.export.wikitext-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-ts.export.wikitext :as wikitext]))

(deftest headings
  (is (= "! One" (wikitext/md->wikitext "# One")))
  (is (= "!!! Three" (wikitext/md->wikitext "### Three")))
  (is (= "!!!!!! Six" (wikitext/md->wikitext "###### Six"))))

(deftest emphasis
  (is (= "''bold'' and //italic//"
         (wikitext/md->wikitext "**bold** and *italic*")))
  (is (= "//also italic//"
         (wikitext/md->wikitext "_also italic_")))
  (is (= "''bold with //nested italic//''"
         (wikitext/md->wikitext "**bold with *nested italic***"))))

(deftest strikethrough-passes-through
  ;; no GFM strikethrough extension: `~~x~~` stays literal, which TiddlyWiki
  ;; itself renders as strikethrough
  (is (= "~~gone~~" (wikitext/md->wikitext "~~gone~~"))))

(deftest inline-code
  (is (= "before `(+ 1 2)` after"
         (wikitext/md->wikitext "before `(+ 1 2)` after"))))

(deftest inline-code-containing-backticks
  ;; a double-backtick span with a backtick inside cannot be wrapped in
  ;; single backticks -- the unbalanced delimiters swallow the rest of the
  ;; text into a code region
  (is (= "press <code>&#96;</code> to toggle"
         (wikitext/md->wikitext "press `` ` `` to toggle")))
  (is (= "a <code>x&#96;y</code> span"
         (wikitext/md->wikitext "a `` x`y `` span"))))

(deftest code-blocks
  (testing "fenced block with language info survives"
    (is (= "```clojure\n(defn f [x] x)\n```"
           (wikitext/md->wikitext "```clojure\n(defn f [x] x)\n```"))))
  (testing "fenced block without info"
    (is (= "```\nplain\n```"
           (wikitext/md->wikitext "```\nplain\n```"))))
  (testing "indented code becomes fenced"
    (is (= "```\nindented\n```"
           (wikitext/md->wikitext "    indented"))))
  (testing "markdown syntax inside a code block is not converted"
    (is (= "```\n# not a heading\n**not bold**\n```"
           (wikitext/md->wikitext "```\n# not a heading\n**not bold**\n```")))))

(deftest unordered-lists
  (is (= "* a\n* b"
         (wikitext/md->wikitext "- a\n- b")))
  (testing "nesting uses marker repetition, not indentation"
    (is (= "* a\n** a1\n** a2\n* b"
           (wikitext/md->wikitext "- a\n  - a1\n  - a2\n- b")))))

(deftest ordered-lists
  (is (= "# first\n# second"
         (wikitext/md->wikitext "1. first\n2. second")))
  (testing "mixed nesting"
    (is (= "# first\n#* inner\n# second"
           (wikitext/md->wikitext "1. first\n   - inner\n2. second")))))

(deftest blockquotes
  (is (= "<<<\nquoted text\n<<<"
         (wikitext/md->wikitext "> quoted text"))))

(deftest external-links
  (testing "titled link becomes [[text|url]]"
    (is (= "[[Example|https://example.com]]"
           (wikitext/md->wikitext "[Example](https://example.com)"))))
  (testing "autolink collapses to the bare url (TiddlyWiki auto-links it)"
    (is (= "https://example.com"
           (wikitext/md->wikitext "<https://example.com>"))))
  (testing "bare url in text is left alone"
    (is (= "see https://example.com here"
           (wikitext/md->wikitext "see https://example.com here")))))

(deftest images
  (is (= "[img[pic.png]]"
         (wikitext/md->wikitext "![alt text](pic.png)"))))

(deftest wiki-links
  (testing "plain wiki link is unchanged"
    (is (= "go to [[HelloWorld]] now"
           (wikitext/md->wikitext "go to [[HelloWorld]] now"))))
  (testing "aliased wiki link swaps target and display"
    (is (= "[[hello there|HelloWorld]]"
           (wikitext/md->wikitext "[[HelloWorld|hello there]]"))))
  (testing "aliased wiki link inside inline code is not swapped"
    (is (= "`[[HelloWorld|hello]]`"
           (wikitext/md->wikitext "`[[HelloWorld|hello]]`")))))

(deftest gfm-tables
  (is (= "|a|b|h\n|1|2|\n|3|4|"
         (wikitext/md->wikitext "|a|b|\n|---|---|\n|1|2|\n|3|4|")))
  (testing "inline markup inside cells converts"
    (is (= "|''x''|h\n|//y//|"
           (wikitext/md->wikitext "|**x**|\n|---|\n|*y*|")))))

(deftest double-comma-tables
  (is (= "|x|y|\n|z|w|"
         (wikitext/md->wikitext "x,,y\nz,,w")))
  (testing "cells are trimmed"
    (is (= "|x|y|"
           (wikitext/md->wikitext "x ,, y"))))
  (testing "surrounding markdown still converts"
    (is (= "! Title\n\n|a|1|\n|b|2|\n\ntail"
           (wikitext/md->wikitext "# Title\na,,1\nb,,2\ntail")))))

(deftest thematic-breaks
  (is (= "before\n\n---\n\nafter"
         (wikitext/md->wikitext "before\n\n---\n\nafter"))))

(deftest html-passthrough
  (is (= "<div class='note'>hi</div>"
         (wikitext/md->wikitext "<div class='note'>hi</div>")))
  (is (= "text with <br> inline"
         (wikitext/md->wikitext "text with <br> inline"))))

(deftest line-breaks
  (testing "soft break joins with a space"
    (is (= "line one line two"
           (wikitext/md->wikitext "line one\nline two"))))
  (testing "hard break keeps the newline"
    (is (= "line one\nline two"
           (wikitext/md->wikitext "line one  \nline two")))))

(deftest empty-input
  (is (= "" (wikitext/md->wikitext "")))
  (is (= "" (wikitext/md->wikitext "\n\n"))))

(deftest mixed-document
  (let [markdown (str "# Notes\n"
                      "\n"
                      "Some **bold** text linking [[OtherPage]] and\n"
                      "[[TargetPage|a friendly name]].\n"
                      "\n"
                      "- item one\n"
                      "- item two with `code |> span`\n"
                      "\n"
                      "```clojure\n"
                      "(println \"unchanged **here**\")\n"
                      "```\n")
        expected (str "! Notes\n"
                      "\n"
                      "Some ''bold'' text linking [[OtherPage]] and "
                      "[[a friendly name|TargetPage]].\n"
                      "\n"
                      "* item one\n"
                      "* item two with `code |> span`\n"
                      "\n"
                      "```clojure\n"
                      "(println \"unchanged **here**\")\n"
                      "```")]
    (is (= expected (wikitext/md->wikitext markdown)))))
