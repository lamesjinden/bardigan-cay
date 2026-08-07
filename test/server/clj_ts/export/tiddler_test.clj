(ns clj-ts.export.tiddler-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-ts.cards.parsing :as parsing]
            [clj-ts.export.tiddler :as tiddler]
            [clj-ts.query.facts-db :as facts]
            [clj-ts.storage.page-store :as pagestore])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- parse-card [source]
  (first (parsing/raw-text->card-maps source)))

(def ^:private ctx {:page-name "TestPage" :index 0})

(defn- export [source]
  (tiddler/export-card ctx (parse-card source)))

;; region source-passthrough types

(deftest markdown-card
  (is (= "! Title\n\nSome ''bold'' text"
         (:wikitext (export "# Title\n\nSome **bold** text")))))

(deftest manual-copy-card
  (is (= "@@.manual-copy\n! Copied\n@@"
         (:wikitext (export ":manual-copy\n\n# Copied")))))

(deftest raw-card
  (is (= "```\n# not converted\n```"
         (:wikitext (export ":raw\n\n# not converted")))))

(deftest code-card
  (is (= "```\n(defn f [x] x)\n```"
         (:wikitext (export ":code\n\n(defn f [x] x)")))))

;; endregion

;; region captured-output types

(deftest evalraw-card
  (is (= "```\n3\n```"
         (:wikitext (export ":evalraw\n\n(+ 1 2)")))))

(deftest evalmd-card
  (is (= "! Generated"
         (:wikitext (export ":evalmd\n\n(str \"# Generated\")")))))

(deftest bookmark-card
  (is (= "[[An Example|https://example.com]]"
         (:wikitext (export ":bookmark\n\n{:url \"https://example.com\" :title \"An Example\"}")))))

(deftest patterning-card
  (let [{:keys [wikitext assets]}
        (export ":patterning\n\n(clock-rotate 5 (poly 0 0.3 0.2 5 {}))")]
    (is (= "[img[TestPage/patterning-0.svg]]" wikitext))
    (is (= 1 (count assets)))
    (is (= "TestPage/patterning-0.svg" (:title (first assets))))
    (is (= "image/svg+xml" (:type (first assets))))
    ;; without the namespace declaration browsers refuse to render the
    ;; data-uri image TiddlyWiki generates for svg tiddlers
    (is (str/includes? (:text (first assets)) "<svg xmlns=\"http://www.w3.org/2000/svg\""))))

(deftest network-card
  (let [{:keys [wikitext assets]}
        (export (str ":network\n\n"
                     "{:nodes [[1 \"HelloWorld\" 180 60] [2 \"CardiganBay\" 100 200]]\n"
                     " :arcs [[1 2]]}"))]
    (is (= "[img[TestPage/network-0.svg]]" wikitext))
    (is (str/includes? (:text (first assets)) "<svg xmlns=\"http://www.w3.org/2000/svg\""))
    (is (str/includes? (:text (first assets)) "HelloWorld"))))

;; endregion

;; region media references

(deftest filelink-card
  (testing "with label"
    (is (= "[[The Doc|media/doc.pdf]]"
           (:wikitext (export ":filelink\n\n{:file-name \"doc.pdf\" :label \"The Doc\"}")))))
  (testing "without label"
    (is (= "[[doc.pdf|media/doc.pdf]]"
           (:wikitext (export ":filelink\n\n{:file-name \"doc.pdf\"}"))))))

(deftest embed-media-img-card
  (is (= "[img[media/cat.png]]"
         (:wikitext (export ":embed\n\n{:type :media-img :src \"cat.png\"}")))))

(deftest embed-youtube-card
  (let [{:keys [wikitext]}
        (export ":embed\n\n{:type :youtube :url \"https://www.youtube.com/watch?v=abc123\"}")]
    (is (str/includes? wikitext "iframe"))
    (is (str/includes? wikitext "abc123"))))

;; endregion

;; region frozen dynamic types

(deftest workspace-card-freezes
  (let [{:keys [wikitext]} (export ":workspace\n\n(println \"hi\")")]
    (is (str/includes? wikitext "frozen ClojureScript workspace"))
    (is (str/includes? wikitext "```clojure\n(println \"hi\")\n```"))))

(deftest workspace-output-is-captured-for-pure-code
  (testing "hiccup result is rendered"
    (let [{:keys [wikitext]} (export ":workspace\n\n[:ul (map (fn [x] [:li x]) (range 2))]")]
      (is (str/includes? wikitext "output captured at export time"))
      (is (str/includes? wikitext "<ul><li>0</li><li>1</li></ul>"))))
  (testing "string result passes through"
    (let [{:keys [wikitext]} (export ":workspace\n\n(str \"<b>\" (+ 1 2) \"</b>\")")]
      (is (str/includes? wikitext "<b>3</b>"))))
  (testing "client-only code yields no output section"
    (let [{:keys [wikitext]} (export ":workspace\n\n(r/atom 1)")]
      (is (str/includes? wikitext "(r/atom 1)"))
      (is (not (str/includes? wikitext "output captured"))))))

(deftest workspace-public-private-split
  (let [{:keys [wikitext]}
        (export ":workspace\n\n(defn hidden [x] (* x 2))\n;;;;PUBLIC\n(str (hidden 21))")]
    (testing "private code is evaluated but not displayed"
      (is (not (str/includes? wikitext "defn hidden")))
      (is (str/includes? wikitext "```clojure\n(str (hidden 21))\n```"))
      (is (str/includes? wikitext "42")))))

(deftest workspace-config-map-is-not-displayed
  (let [{:keys [wikitext]}
        (export ":workspace\n\n{:eval-on-load true}\n\n(str (+ 1 1))")]
    (is (not (str/includes? wikitext ":eval-on-load")))
    (is (str/includes? wikitext "```clojure\n(str (+ 1 1))\n```"))))

(deftest graph-card-freezes
  (let [{:keys [wikitext]} (export ":graph\n\n{:data []}")]
    (is (str/includes? wikitext "frozen graph card"))
    (is (str/includes? wikitext "{:data []}"))))

(deftest map-declared-graph-card-freezes-the-map
  ;; graph cards are usually written as a bare type-declaring map with no
  ;; body after it; the map itself is the source to display
  (let [source "{:card/type :graph\n :data [{:x [1 2] :y [3 4] :type \"line\"}]}"
        {:keys [wikitext]} (export source)]
    (is (str/includes? wikitext "frozen graph card"))
    (is (str/includes? wikitext ":data [{:x [1 2] :y [3 4] :type \"line\"}]"))))

;; endregion

;; region dropped / unknown / failing

(deftest system-card-is-dropped
  (is (nil? (:wikitext (export ":system\n\n{:command :Search}")))))

(deftest unknown-type-falls-back-to-source
  (let [{:keys [wikitext]} (export ":frobnicate\n\nmystery content")]
    (is (str/includes? wikitext "unrecognised card type :frobnicate"))
    (is (str/includes? wikitext "mystery content"))))

(deftest failing-card-becomes-error-block
  (let [{:keys [wikitext]} (export ":evalraw\n\n(throw (ex-info \"boom\" {}))")]
    (is (str/includes? wikitext "error exporting :evalraw card"))
    (is (str/includes? wikitext "boom"))
    (is (str/includes? wikitext "(throw (ex-info \"boom\" {}))"))))

;; endregion

;; region page assembly

(deftest page-assembly
  (let [{:keys [text assets]}
        (tiddler/page->tiddler-content nil "TestPage"
                                       (str "# First card\n"
                                            "----\n"
                                            ":system\n\n{:command :Search}\n"
                                            "----\n"
                                            ":raw\n\nsecond card\n"))]
    (is (= (str "! First card"
                "\n\n---\n\n"
                "```\nsecond card\n```")
           text))
    (is (= [] assets))))

;; endregion

;; region cards needing a wiki (transclude, deadline)

(defn- temp-wiki
  "Creates a throwaway page directory with the given pages and returns a
  minimal server snapshot over it."
  [pages]
  (let [dir (-> (Files/createTempDirectory "bc-tiddler-test" (make-array FileAttribute 0))
                (.toFile))]
    (.mkdirs (io/file dir "system"))
    (doseq [[page-name source] pages]
      (spit (io/file dir (str page-name ".md")) source))
    {:page-store (pagestore/make-page-store (str dir))
     :facts-db (reify facts/IFactsDb
                 (all-pages [_] (vec (keys pages))))}))

(deftest transclude-card-inlines-target
  (let [target-source "# Transcluded heading\n\nwith **bold** text"
        target-hash (:hash (parse-card target-source))
        snapshot (temp-wiki {"PageB" target-source})
        {:keys [wikitext]}
        (tiddler/export-card (assoc ctx :server-snapshot snapshot)
                             (parse-card (str ":transclude\n\n{:from \"PageB\" :ids [\""
                                              target-hash "\"]}")))]
    (is (= "! Transcluded heading\n\nwith ''bold'' text" wikitext))))

(deftest nested-transclusion-is-not-followed
  (let [snapshot (temp-wiki {"PageB" ":transclude\n\n{:from \"PageC\" :ids []}"})
        outer-hash (:hash (parse-card ":transclude\n\n{:from \"PageC\" :ids []}"))
        {:keys [wikitext]}
        (tiddler/export-card (assoc ctx :server-snapshot snapshot)
                             (parse-card (str ":transclude\n\n{:from \"PageB\" :ids [\""
                                              outer-hash "\"]}")))]
    (is (str/includes? wikitext "nested transclusion is not exported"))))

(deftest deadline-card-freezes-aggregation
  (let [snapshot (temp-wiki {"PageA" "some text\nfinish the thing deadline: 2026-09-01\n"})
        {:keys [wikitext]}
        (tiddler/export-card (assoc ctx :server-snapshot snapshot)
                             (parse-card ":deadline\n"))]
    (is (str/includes? wikitext "[[PageA]]"))
    (is (str/includes? wikitext "2026-09-01"))
    (is (str/starts-with? wikitext "|Page|When|Deadline|h"))))

;; endregion
