(ns wiki.bc.revision-test
  (:require [cljs.test :refer [deftest is testing]]
            [wiki.bc.revision :as revision]))

(def ^:private entries
  [{"sha" "ccc"} {"sha" "bbb"} {"sha" "aaa"}])

(deftest position-test
  (testing "numbers from the oldest, with the neighbours either side"
    (is (= {:number 2 :total 3 :older {"sha" "aaa"} :newer {"sha" "ccc"}}
           (revision/position entries {"sha" "bbb"}))))
  (testing "the newest revision has nothing newer"
    (is (= {:number 3 :total 3 :older {"sha" "bbb"} :newer nil}
           (revision/position entries {"sha" "ccc"}))))
  (testing "the oldest revision has nothing older"
    (is (= {:number 1 :total 3 :older nil :newer {"sha" "bbb"}}
           (revision/position entries {"sha" "aaa"}))))
  (testing "a commit that did not touch the page has no position"
    (is (nil? (revision/position entries {"sha" "zzz"})))
    (is (nil? (revision/position [] {"sha" "aaa"})))))

(deftest former-name-test
  (testing "a revision from before a rename names the page as it was"
    (is (= "OldName" (revision/former-name {"page_name" "OldName"} "NewName"))))
  (testing "no former name when the page was called the same"
    (is (nil? (revision/former-name {"page_name" "NewName"} "NewName"))))
  (testing "no former name when the page did not exist, or off a snapshot"
    (is (nil? (revision/former-name {"page_name" nil} "NewName")))
    (is (nil? (revision/former-name nil "NewName")))))
