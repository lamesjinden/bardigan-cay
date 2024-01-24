(ns clj-ts.card-test
  (:require [cljs.test :refer (deftest is)]
            [clj-ts.card :as card]))

(deftest ->card-configuration_simple-card-without-card-configuration
  (let [card {"source_data" "# heading"}
        card-configuration (card/->card-configuration card)]
    (is (nil? card-configuration))))

(deftest ->card-configuration_card-with-card-configuration
  (let [card {"source_data" "{:display :collapsed}\n# heading"}
        card-configuration (card/->card-configuration card)]
    (is (map? card-configuration))))

(deftest ->card-configuration_card-configuration-is-not-first-token
  (let [card {"source_data" "# heading 1\n{:display :collapsed}\n# heading 2"}
        card-configuration (card/->card-configuration card)]
    (is (nil? card-configuration))))