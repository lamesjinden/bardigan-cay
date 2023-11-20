(ns clj-ts.core-test
  (:require [clojure.test :refer :all]
            [clj-ts.common :as common]
            [clj-ts.card-server :as card-server]))


(deftest card-server-stuff
         (testing "Card Server stuff"
                  (println "card-server .as-map "
                           (-> (card-server/server-state) .as-map))))

(deftest double-bracket
         (testing "Double bracket links"
                  (is (= (common/double-bracket-links "hello world") "hello world"))
                  (is (= (common/double-bracket-links "hello [[world]]") "hello <span class=\"wikilink\" data=\"world\">world</span>"))))

(deftest double-comma-table
         (testing "Double comma table"
                  (is (= (common/double-comma-table "hello world") "hello world"))
                  (is (= (common/double-comma-table "hello,, world")
                         "<div class=\"embed_div\"><table class='double-comma-table'>
              <tr><td>hello</td><td> world</td></tr>
              </table></div>"))
                  (is (= (common/double-comma-table "hello\nteenage,,america")
                         "hello
              <div class=\"embed_div\"><table class='double-comma-table'>
              <tr><td>teenage</td><td>america</td></tr>
              </table></div>"))))

(defn hs [cards]
      (map #(:hash %) cards))

(deftest card-reordering
         (testing "Card re-ordering"
                  (let [cards (map acard (range 10))
                        hc1 (-> cards first :hash)
                        hc4 (-> cards (nth 3) :hash)
                        hcl (-> cards last :hash)]
                       (is (= (hs (common/move-card-up cards hc1)) (hs cards)))

                       (is (= (hs (common/move-card-up cards hc4))
                              (hs (concat (take 2 cards)
                                          [(nth cards 3)]
                                          [(nth cards 2)]
                                          (drop 4 cards)
                                          ))))

                       (is (= (hs (common/move-card-down cards hcl)) (hs cards)))

                       (is (= (hs (common/move-card-down cards hc4))
                              (hs (concat (take 3 cards)
                                          [(nth cards 4)]
                                          [(nth cards 3)]
                                          (drop 5 cards))))))))

