(ns clj-ts.export.wikitext
  "Converts BardiganCay-flavoured markdown to TiddlyWiki WikiText.

  The conversion walks the commonmark-java AST rather than rewriting
  strings, so markdown structure (code blocks, emphasis nesting, tables)
  is honoured. Two BC dialect features are handled outside the AST walk:

  - double-comma tables: contiguous lines containing `,,` become native
    TiddlyWiki tables (a pre-pass, since commonmark has no notion of them)
  - wiki links: `[[Page]]` passes through unchanged, but the aliased form
    swaps sides -- BC writes `[[target|display]]` where TiddlyWiki expects
    `[[display|target]]`. The swap is applied to assembled inline text with
    code spans shielded behind sentinels, so links inside inline code are
    left alone."
  (:require [clojure.string :as str]
            [clj-ts.util :as util])
  (:import [org.commonmark.ext.gfm.tables TableBlock TableHead TablesExtension]
           [org.commonmark.node BlockQuote BulletList Code Emphasis
            FencedCodeBlock HardLineBreak Heading HtmlBlock HtmlInline Image
            IndentedCodeBlock Link Node OrderedList Paragraph SoftLineBreak
            StrongEmphasis Text ThematicBreak]
           [org.commonmark.parser Parser]))

(def ^:private ^Parser markdown-parser
  (.. (Parser/builder)
      (extensions [(TablesExtension/create)])
      (build)))

(defn- node-children [^Node node]
  (loop [child (.getFirstChild node)
         acc []]
    (if child
      (recur (.getNext child) (conj acc child))
      acc)))

;; region wiki links

;; BC's aliased form is [[target|display]]; TiddlyWiki's is [[display|target]].
(def ^:private aliased-wiki-link-pattern #"\[\[([\w\s-:]+?)\|([\w\s\-:\.]+?)\]\]")

(defn- swap-aliased-wiki-links [text]
  (str/replace text aliased-wiki-link-pattern "[[$2|$1]]"))

;; endregion

;; region inline rendering

;; Inline code spans are emitted as \x00<index>\x00 sentinels and restored
;; after the wiki-link swap, so their content is never rewritten.

(declare render-inline)

(defn- inline-code
  "A code span whose content contains a backtick cannot be delimited
  reliably in WikiText; fall back to a <code> element. TiddlyWiki parses
  the content of HTML elements as WikiText, so backticks must also be
  entity-encoded or they open a runaway code region."
  [literal]
  (if (str/includes? literal "`")
    (str "<code>"
         (-> (util/html-escape literal)
             (str/replace "`" "&#96;"))
         "</code>")
    (str "`" literal "`")))

(defn- render-inline-children [^Node node codes]
  (apply str (map #(render-inline % codes) (node-children node))))

(defn- render-link [^Link node codes]
  (let [destination (.getDestination node)
        text (render-inline-children node codes)]
    (if (or (str/blank? text) (= text destination))
      destination
      (str "[[" text "|" destination "]]"))))

(defn- render-inline [^Node node codes]
  (condp instance? node
    Text (.getLiteral ^Text node)
    SoftLineBreak " "
    HardLineBreak "\n"
    Emphasis (str "//" (render-inline-children node codes) "//")
    StrongEmphasis (str "''" (render-inline-children node codes) "''")
    Code (let [index (count @codes)]
           (swap! codes conj (inline-code (.getLiteral ^Code node)))
           (str "\u0000" index "\u0000"))
    Link (render-link node codes)
    Image (str "[img[" (.getDestination ^Image node) "]]")
    HtmlInline (.getLiteral ^HtmlInline node)
    (render-inline-children node codes)))

(defn- restore-code-spans [text codes]
  (str/replace text #"\x00(\d+)\x00"
               (fn [[_ index]]
                 (nth codes (parse-long index)))))

(defn- inline-content
  "WikiText for the inline children of a block node."
  [^Node node]
  (let [codes (atom [])
        rendered (render-inline-children node codes)]
    (-> rendered
        (swap-aliased-wiki-links)
        (restore-code-spans @codes))))

;; endregion

;; region block rendering

(declare block->wikitext)

(defn- blocks->wikitext [nodes]
  (->> nodes
       (map block->wikitext)
       (remove str/blank?)
       (str/join "\n\n")))

(defn- strip-trailing-newline [s]
  (str/replace s #"\n\z" ""))

(defn- code-block [info literal]
  (str "```" info "\n" (strip-trailing-newline literal) "\n```"))

;; TiddlyWiki nests lists by repeating the marker (`**`, `#*`, ...) rather
;; than by indentation, so the accumulated marker prefix is threaded down.
(declare list->lines)

(defn- list-item->lines [^Node item prefix]
  (let [parts (map (fn [^Node child]
                     (condp instance? child
                       BulletList {:lines (list->lines child (str prefix "*"))}
                       OrderedList {:lines (list->lines child (str prefix "#"))}
                       {:inline (inline-content child)}))
                   (node-children item))
        own-line (str prefix " " (str/join " " (keep :inline parts)))]
    (cons own-line (mapcat :lines parts))))

(defn- list->lines [^Node list-node prefix]
  (mapcat #(list-item->lines % prefix) (node-children list-node)))

(defn- table-row [^Node row header?]
  (str "|"
       (str/join "|" (map inline-content (node-children row)))
       "|"
       (when header? "h")))

(defn- table->wikitext [^TableBlock table]
  (->> (node-children table)
       (mapcat (fn [^Node section]
                 (let [header? (instance? TableHead section)]
                   (map #(table-row % header?) (node-children section)))))
       (str/join "\n")))

(defn- block->wikitext [^Node node]
  (condp instance? node
    Heading (str (apply str (repeat (.getLevel ^Heading node) "!"))
                 " "
                 (inline-content node))
    Paragraph (inline-content node)
    FencedCodeBlock (code-block (str (.getInfo ^FencedCodeBlock node))
                                (.getLiteral ^FencedCodeBlock node))
    IndentedCodeBlock (code-block "" (.getLiteral ^IndentedCodeBlock node))
    BlockQuote (str "<<<\n" (blocks->wikitext (node-children node)) "\n<<<")
    BulletList (str/join "\n" (list->lines node "*"))
    OrderedList (str/join "\n" (list->lines node "#"))
    ThematicBreak "---"
    HtmlBlock (str/trimr (.getLiteral ^HtmlBlock node))
    TableBlock (table->wikitext node)
    (inline-content node)))

;; endregion

;; region double-comma tables

;; Matches the legacy behaviour: any line containing `,,` is a table row and
;; contiguous rows form one table. Cells are trimmed because leading/trailing
;; spaces carry alignment meaning in TiddlyWiki tables.

(defn- dc-table-line? [line]
  (str/includes? line ",,"))

(defn- dc-table->wikitext [text]
  (->> (str/split-lines text)
       (map (fn [line]
              (str "|"
                   (str/join "|" (map str/trim (str/split line #",,")))
                   "|")))
       (str/join "\n")))

(defn- segment-source
  "Splits source into alternating markdown and double-comma-table segments."
  [source]
  (->> (str/split-lines source)
       (partition-by dc-table-line?)
       (map (fn [lines]
              {:dc-table? (dc-table-line? (first lines))
               :text (str/join "\n" lines)}))))

;; endregion

(defn md->wikitext
  "Converts BardiganCay markdown source to TiddlyWiki WikiText."
  [source]
  (->> (segment-source source)
       (map (fn [{:keys [dc-table? text]}]
              (if dc-table?
                (dc-table->wikitext text)
                (blocks->wikitext (node-children (.parse markdown-parser text))))))
       (remove str/blank?)
       (str/join "\n\n")))
