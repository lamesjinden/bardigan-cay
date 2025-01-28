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

(deftest auto-links-test
  (let [example-md-string "### A Heading
                           
                           a string referencing https://google.com should become a link
                           "
        rendered (clj-ts.common/auto-links example-md-string)
        expected "<a href=\"https://google.com\">https://google.com</a>"]
    (is (s/includes? rendered expected))))

(deftest double-bracket-links-test
  (let [example-md-string
        "<ul><li>a broken link [[orphan]]</li><li>a link to [[test02|test with a alt title]]</li></ul>"
        rendered (clj-ts.common/double-bracket-links example-md-string)]
    (is (s/includes? rendered "<a class='wikilink' data='orphan' href='/pages/orphan'>orphan</a>"))
    (is (s/includes? rendered "<a class='wikilink' data='test02' href='/pages/test02'>test with a alt title</a>"))))
