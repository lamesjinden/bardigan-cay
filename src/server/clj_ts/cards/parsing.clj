(ns clj-ts.cards.parsing
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clj-ts.util :as util])
  (:import [org.commonmark.ext.gfm.tables TableBlock TablesExtension]
           [org.commonmark.node Code FencedCodeBlock IndentedCodeBlock Node SourceSpan]
           [org.commonmark.parser IncludeSourceSpans Parser]))

;; -----------------------------------------------------------------------------
;; Card splitting
;;
;; A page is split into cards at delimiter lines: lines consisting solely of
;; four or more hyphens (trailing whitespace allowed). A delimiter line is
;; ignored when it falls inside a protected markdown element -- a code block
;; (fenced or indented), an inline code span, or a table -- so that standard
;; markdown inside a card survives splitting.
;;
;; Protected regions are found by parsing the page with commonmark-java, which
;; records the source span (character offsets) of every parsed node.
;; -----------------------------------------------------------------------------

(def ^:private ^Parser markdown-parser
  (.. (Parser/builder)
      (extensions [(TablesExtension/create)])
      (includeSourceSpans IncludeSourceSpans/BLOCKS_AND_INLINES)
      (build)))

(defn- node-children [^Node node]
  (->> (.getFirstChild node)
       (iterate (fn [^Node n] (.getNext n)))
       (take-while some?)))

(def ^:private protected-node-classes
  #{Code FencedCodeBlock IndentedCodeBlock TableBlock})

(defn- node->range
  "The [start end) character range covered by a node's source spans."
  [^Node node]
  (when-let [spans (seq (.getSourceSpans node))]
    [(reduce min (map (fn [^SourceSpan span] (.getInputIndex span)) spans))
     (reduce max (map (fn [^SourceSpan span] (+ (.getInputIndex span) (.getLength span))) spans))]))

(defn find-protected-ranges
  "Find all [start end) character ranges protected from card splitting:
   code blocks (fenced and indented), inline code spans, and tables."
  [text]
  (->> (.parse markdown-parser text)
       (tree-seq some? node-children)
       (filter #(contains? protected-node-classes (class %)))
       (keep node->range)))

(defn in-protected-range?
  "Check if a position falls within any protected range."
  [pos ranges]
  (some (fn [[start end]]
          (and (>= pos start) (< pos end)))
        ranges))

(defn find-delimiter-positions
  "Find all card delimiter lines -- lines consisting solely of four or more
   hyphens, with optional trailing whitespace -- and their character positions.
   Returns a sequence of {:start :end} maps."
  [text]
  (let [matcher (re-matcher #"(?m)^-{4,}[ \t]*$" text)]
    (loop [positions []]
      (if (.find matcher)
        (recur (conj positions {:start (.start matcher)
                                :end   (.end matcher)}))
        positions))))

(defn split-at-positions
  "Split text at the given delimiter positions.
   Returns a sequence of trimmed, non-blank strings."
  [text positions]
  (if (empty? positions)
    (let [trimmed (str/trim text)]
      (if (str/blank? trimmed) [] [trimmed]))
    (let [;; Add implicit start and end positions
          all-positions (concat [{:end 0}]
                                positions
                                [{:start (count text)}])
          ;; Extract segments between delimiters
          segments (map (fn [[prev curr]]
                          (subs text (:end prev) (:start curr)))
                        (partition 2 1 all-positions))]
      (->> segments
           (map str/trim)
           (remove str/blank?)))))

(defn split-by-hyphens
  "Split input text into cards at delimiter lines (four or more hyphens),
   ignoring delimiter lines that fall inside protected markdown elements
   (code blocks, inline code spans, tables).
   Returns a sequence of trimmed, non-blank card texts."
  [input]
  (let [protected-ranges (find-protected-ranges input)
        all-delimiters (find-delimiter-positions input)
        valid-delimiters (remove #(in-protected-range? (:start %) protected-ranges)
                                 all-delimiters)]
    (split-at-positions input valid-delimiters)))

(defn try-read [reader]
  (try
    (edn/read reader)
    (catch Exception _e
      nil)))

(defn try-read-string [s]
  (with-open [reader (util/string->reader s)]
    (try-read reader)))

(defn type-declaring-map? [x]
  (and (map? x) (:card/type x)))

(defn partition-raw-card-text [raw-card-text]
  (let [card-text (str/trim raw-card-text)]
    (try
      (with-open [reader (util/string->reader card-text)]
        (let [first-token (try-read reader)]
          (cond
            (keyword? first-token)
            {:source-type first-token
             :source-body card-text
             :tokens [{:type :keyword
                       :value first-token}
                      {:type :unknown
                       :value (str/trim (slurp reader))}]}

            (type-declaring-map? first-token)
            {:source-type             (:card/type first-token)
             :source-type-configured? true
             :source-body             card-text
             :tokens [{:type :map
                       :value first-token}
                      {:type :unknown
                       :value (str/trim (slurp reader))}]}

            ;; support card-configuration maps without types;
            ;; still allows :markdown to be implicit
            (map? first-token)
            {:source-body card-text
             :tokens [{:type :map
                       :value first-token}
                      {:type :unknown
                       :value (str/trim (slurp reader))}]}

            :else
            {:source-body card-text})))
      (catch Exception _e
        {:source-type nil
         :source-body card-text}))))

(defn raw-card-text->card-map
  "
  Parses raw-card-text and returns a map of card data.

  if raw-card-text begins with a token that can be read as a keyword (as defined by clojure.edn/read),
  then that value is used as the card source_type;

  otherwise, if raw-card-text begins with a 'type-declaring-map' (as defined by 'type-declaring-map?),
  then the value associated to key :card/type is used as the source_type;

  otherwise, the card source_type is implicitly assigned as :markdown.
  "
  [raw-card-text]
  (let [{:keys [source-type source-body source-type-configured?] :as card-map} (partition-raw-card-text raw-card-text)
        card-hash (util/hash-it source-body)]
    (if (nil? source-type)
      (-> card-map
          (assoc :source_type           :markdown)
          (assoc :source_data           source-body)
          (assoc :source_type_implicit? true)
          (assoc :hash card-hash))
      (-> card-map
          (assoc :source_type             source-type)
          (assoc :source_data             source-body)
          (assoc :source_type_configured? source-type-configured?)
          (assoc :hash card-hash)))))

(defn raw-text->card-maps [raw]
  (->> raw
       (split-by-hyphens)
       (map raw-card-text->card-map)))

(defn card-map->card-data [card-map]
  (let [{[a b :as _tokens] :tokens} card-map
        card-data (if-let [readable-token (when (= :keyword (:type a))
                                            (:value b))]
                    (try-read-string readable-token)
                    (:value a))]
    card-data))

(comment

  (require 'clojure.test)
  (clojure.test/run-tests)

  ;
  )
