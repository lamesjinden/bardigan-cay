(ns clj-ts.core-test
  (:require [clojure.test :refer :all]
            [clj-ts.common :as common]
            [clj-ts.card-server :as card-server]

            [clj-ts.command-line :as command-line]
            ))


(deftest card-server-stuff
  (testing "Card Server stuff"
    (println "card-server .as-map "
             (-> (card-server/server-state) .as-map))))

(deftest basic-cards
  (testing "Making and analysing cards"
    (let [c (common/package-card
             1
             :markdown :another "hello teenage america" "goodbye cruel world" )]
      (is (= (:source_type c) :markdown))
      (is (= (:render_type c) :another))
      (is (= (:id c) 1))
      (is (= (:source_data c) "hello teenage america"))
      (is (= (:server_prepared_data c) "goodbye cruel world")))))

(deftest raw-and-cards
  (testing "raw to card two way conversion"
    (let [r1 "hello teenage america"
          [t1 d1] (common/raw-card-text->raw-card-map r1)
          r2 "
:unusual
hello teenage america"
          [t2 d2] (common/raw-card-text->raw-card-map r2)
          c1 (common/package-card 1 t1 t1 d1 d1)
          c2 (common/package-card 2 t2 t2 d2 d2)]
      (is (= t1 :markdown))
      (is (= d1 "hello teenage america"))
      (is (= t2 :unusual))
      (is (= d2 "\nhello teenage america"))
      (is (= (common/card->raw c1) r1))
      (is (= (common/card->raw c2) r2))
      )))



(deftest basic-search-and-replace
  (testing "Searching cards"
    (let [cards [(common/package-card 1 :a :b "hello" "hello")
                 (common/package-card 2 :a :b "teenage" "teenage")
                 (common/package-card 3 :a :b "america" "america")]
          h (-> cards second :hash)
          new-card (common/package-card 22 :b :a "boomer" "boomer")
          h2 (:hash new-card)
          c2 (common/sub-card cards #(= 2 (:id %)) new-card )
          nc2 (common/package-card 33 :c :d "brazil" "brazil")
          c3 (common/sub-card cards #(= 4 (:id %)) nc2)]
      (is (= 2 (->
                (common/find-card-by-hash cards h)
                :id)))
      (is (nil? (common/find-card-by-hash cards "777")))
      (is (= 3 (count c2)))
      (is (= 22 (-> (common/find-card-by-hash c2 h2) :id)))
      (is (= c3 cards)))))

(deftest cards-test
  (testing "Cards"
    (let [cards [(common/package-card 1 :markdown :boo "hello" "hello")
                 (common/package-card 2 :a :b "teenage" "teenage")
                 (common/package-card 3 :a :b "america" "america")]]
      (is (= (common/cards->raw cards)
             "hello
----
:a
teenage
----
:a
america")))))

(deftest double-bracket
  (testing "Double bracket links"
    (is (= (common/double-bracket-links "hello world") "hello world"))
    (is (= (common/double-bracket-links "hello [[world]]") "hello <span class=\"wikilink\" data=\"world\">world</span>"))  ) )

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


(defn acard [id]
  (common/package-card id :markdown :markdown id id))

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
                         (drop 5 cards)))))

      )))


(deftest command-line
  (testing "Command line"
    (let [parse command-line/parser
          s1 "! >>HelloWorld"
          s2 "! +hello +teenage +america"
          s3 "blah blah"
          c1 "----

hello world"
          c2 "----

! >>SomewhereElse
hello teenage america"
          c3 "----

boo
! >>SomewhereElse
goodbye cruel world"

          c4 "----

more

! +another +green +world"
          c5 "----
! >>SpiceWorld
right about now
! +funk +soul +brother"
          c6 "----
some stuff
but +not +actual +tags"
          cmds1 (common/gather-all-commands c1)
          cmds2 (common/gather-all-commands c2)
          cmds5 (common/gather-all-commands c5)]

      (is (command-line/command-line? s1))
      (is (command-line/command-line? s2))
      (is (= false (command-line/command-line? s3)))

      (is (= (parse s1) {:type :Move
                         :page-name "HelloWorld"} ))
      (is (= (parse s2) {:type :Tags
                         :tags [ [:Tag "hello"] [:Tag "teenage"] [:Tag "america"]]}))

      (is (= (-> (parse ">>Boo") :type) :error))


      (is (= false (common/contains-commands? c1)))
      (is (= true (common/contains-commands? c2)))
      (is (= true (common/contains-commands? c3)))
      (is (= true (common/contains-commands? c4)))
      (is (= true (common/contains-commands? c5)))
      (is (= false (common/contains-commands? c6)))

      (is (= 0 (:size cmds1)))
      (is (= 1 (:size cmds2)))
      (is (= 2 (:size cmds5)))

      (is (= false (:has-commands? cmds1)))
      (is (= true (:has-commands? cmds2)))
      (is (= true (:has-commands? cmds5)))

      (is (= false (:has-move? cmds1)))
      (is (= true (:has-move? cmds2)))
      (is (= true (:has-move? cmds5)))

      (is (= false (:has-tags? cmds1) ))
      (is (= false (:has-tags? cmds2)) )
      (is (= true (:has-tags? cmds5)))

      (is (= [:funk :soul :brother] (:tags cmds5)))

      (is (= "SomewhereElse" (:move-destination cmds2)))
      (is (= "SpiceWorld" (:move-destination cmds5)))

      (is (= "right about now" (:stripped cmds5)))
      )))
