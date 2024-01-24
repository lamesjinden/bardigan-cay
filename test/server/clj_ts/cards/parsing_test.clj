(ns clj-ts.cards.parsing-test
  (:require [clojure.test :refer [deftest is]]
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
    (is (= source_data "simple text"))))

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
    (is (= source_data "{:command :search :query \"query string\"}"))))

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