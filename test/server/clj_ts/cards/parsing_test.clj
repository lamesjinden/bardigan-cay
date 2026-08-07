(ns clj-ts.cards.parsing-test
  (:require [clj-ts.common]
            [clj-ts.render]
            [clojure.string :as s]
            [clojure.test :refer [deftest is]]
            [clj-ts.cards.parsing :as parsing]))

(deftest partition-raw-card-text_simple-card-text
  (let [raw-card-text
        "simple text"

        {:keys [source-type source-body]}
        (parsing/partition-raw-card-text raw-card-text)]
    (is (= source-type nil))
    (is (= source-body raw-card-text))))

(deftest raw-card-text->card-map_simple-card-text
  (let [raw-card-text "simple text"

        {:keys [source_type source_type_implicit? source_data]}
        (parsing/raw-card-text->card-map raw-card-text)]
    (is (= source_type :markdown))
    (is (= source_type_implicit? true))
    (is (= source_data raw-card-text))))

(deftest raw-card-text->card-map_explicit-markdown-text
  (let [raw-card-text
        ":markdown
        simple text"

        {:keys [source_type source_type_implicit? source_data]}
        (parsing/raw-card-text->card-map raw-card-text)]
    (is (= source_type :markdown))
    (is (= (boolean source_type_implicit?) false))
    (is (= source_data raw-card-text))))

(deftest raw-card-text->card-map_workspace-with-configuration-map
  (let [raw-card-text
        ":workspace

{:eval-on-load false
 :code-visibility true
 :result-visibility true
 :layout :horizontal}

[:div (str \"Hello Teenage America\")]"

        {:keys [source_type source_type_implicit? source_data]}
        (parsing/raw-card-text->card-map raw-card-text)]
    (is (= source_type :workspace))
    (is (= (boolean source_type_implicit?) false))
    (is (.contains source_data "{:eval-on-load false\n :code-visibility true\n :result-visibility true\n :layout :horizontal}"))
    (is (.contains source_data "[:div (str \"Hello Teenage America\")]"))))

(deftest raw-card-text->card-map_parameter-map-text
  (let [raw-card-text
        ":system
        {:command :search :query \"query string\"}"

        {:keys [source_type source_type_implicit? source_data]}
        (parsing/raw-card-text->card-map raw-card-text)]
    (is (= source_type :system))
    (is (= (boolean source_type_implicit?) false))
    (is (= source_data raw-card-text))))

(deftest raw-card-text->card-map_combined-parameter-map-and-card-configuration-map
  (let [raw-card-text
        "{:command :search :query \"query string\" :card/type :system}"

        {:keys [source_type source_type_implicit? source_data]}
        (parsing/raw-card-text->card-map raw-card-text)]
    (is (= source_type :system))
    (is (= (boolean source_type_implicit?) false))
    (is (= source_data raw-card-text))))

(deftest raw-card-text->card-map_card-configuration-map-header-with-body
  (let [raw-card-text
        "{:card/type :workspace
:eval-on-load false
:code-visibility true
:result-visibility true
:layout :horizontal}

[:div (str \"Hello Teenage America\")]"

        {:keys [source_type source_type_implicit? source_data]}
        (parsing/raw-card-text->card-map raw-card-text)]
    (is (= source_type :workspace))
    (is (= (boolean source_type_implicit?) false))
    (is (.contains source_data "{:card/type :workspace\n:eval-on-load false\n:code-visibility true\n:result-visibility true\n:layout :horizontal}"))
    (is (.contains source_data "[:div (str \"Hello Teenage America\")]"))))

;; region card splitting

(deftest split-by-hyphens_plain-cards
  (is (= ["Card 1" "Card 2" "Card 3"]
         (parsing/split-by-hyphens "Card 1\n----\nCard 2\n----\nCard 3"))))

(deftest split-by-hyphens_longer-hyphen-runs-are-delimiters
  (is (= ["Card 1" "Card 2"]
         (parsing/split-by-hyphens "Card 1\n--------\nCard 2"))))

(deftest split-by-hyphens_delimiter-allows-trailing-whitespace
  (is (= ["Card 1" "Card 2"]
         (parsing/split-by-hyphens "Card 1\n----   \nCard 2"))))

(deftest split-by-hyphens_mid-line-hyphens-are-not-delimiters
  (is (= ["wait----what"]
         (parsing/split-by-hyphens "wait----what"))))

(deftest split-by-hyphens_hyphens-followed-by-text-are-not-delimiters
  (is (= ["Card 1\n---- not a delimiter"]
         (parsing/split-by-hyphens "Card 1\n---- not a delimiter"))))

(deftest split-by-hyphens_fenced-code-block-is-protected
  (is (= ["Card 1" "```\ncode with\n----\ninside\n```" "Card 3"]
         (parsing/split-by-hyphens "Card 1\n----\n```\ncode with\n----\ninside\n```\n----\nCard 3"))))

(deftest split-by-hyphens_unclosed-fence-protects-to-end-of-page
  (is (= ["Card 1" "```\n----\nCard 3"]
         (parsing/split-by-hyphens "Card 1\n----\n```\n----\nCard 3"))))

(deftest split-by-hyphens_indented-code-block-is-protected
  (is (= ["Card 1" "code\n    ----\n    more" "Card 3"]
         (parsing/split-by-hyphens "Card 1\n----\n    code\n    ----\n    more\n----\nCard 3"))))

(deftest split-by-hyphens_table-with-wide-separator-row-is-protected
  (is (= ["Card 1" "| Col A | Col B |\n|-------|-------|\n| 1     | 2     |" "Card 3"]
         (parsing/split-by-hyphens "Card 1\n----\n| Col A | Col B |\n|-------|-------|\n| 1     | 2     |\n----\nCard 3"))))

(deftest split-by-hyphens_table-with-narrow-separator-row-is-protected
  (is (= ["Card 1" "| Col A | Col B |\n|---|---|\n| 1 | 2 |" "Card 3"]
         (parsing/split-by-hyphens "Card 1\n----\n| Col A | Col B |\n|---|---|\n| 1 | 2 |\n----\nCard 3"))))

(deftest split-by-hyphens_mixed-protected-contexts
  (is (= ["Card 1"
          "```\nfenced\n----\nblock\n```"
          "indented block"
          "Card 4"]
         (parsing/split-by-hyphens
          "Card 1\n----\n```\nfenced\n----\nblock\n```\n----\n    indented block\n----\nCard 4"))))

(deftest raw-text->card-maps_page-with-standard-table
  (let [cards (parsing/raw-text->card-maps
               "Intro\n----\n| A | B |\n|-------|-------|\n| 1 | 2 |\n----\nOutro")]
    (is (= 3 (count cards)))
    (is (every? #(= :markdown (:source_type %)) cards))))

;; endregion

;; region rendering

(deftest md->html_renders-standard-markdown-table
  (let [html (clj-ts.render/md->html "| A | B |\n|-------|-------|\n| 1 | 2 |")]
    (is (s/includes? html "<table>"))
    (is (s/includes? html "<th>A</th>"))
    (is (s/includes? html "<td>1</td>"))))

(deftest md->html_renders-double-comma-table
  (let [html (clj-ts.render/md->html "a,,b\nc,,d")]
    (is (s/includes? html "double-comma-table"))
    (is (s/includes? html "<td>a</td>"))
    (is (s/includes? html "<td>d</td>"))))

;; endregion

(deftest double-bracket-links-test
  (let [example-md-string
        "<ul><li>a broken link [[orphan]]</li><li>a link to [[test02|test with a alt title]]</li></ul>"
        rendered (clj-ts.common/double-bracket-links example-md-string)]
    (is (s/includes? rendered "<a class='wikilink' data='orphan' href='/pages/orphan'>orphan</a>"))
    (is (s/includes? rendered "<a class='wikilink' data='test02' href='/pages/test02'>test with a alt title</a>"))))
