(ns wiki.bc.query.index-db-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [wiki.bc.query.index-db :as index-db]
            [wiki.bc.storage.index :as index]
            [wiki.bc.storage.page-store :as pagestore])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-wiki-dir [pages]
  (let [dir (-> (Files/createTempDirectory "bc-index-db-test" (make-array FileAttribute 0))
                (.toFile))]
    (.mkdirs (io/file dir "system"))
    (doseq [[page-name source] pages]
      (spit (io/file dir (str page-name ".md")) source))
    dir))

;; Start -> About, Missing (broken); About -> Start; Lonely -> nothing
(def ^:private corpus
  {"Start"  "welcome to [[About]] and [[Missing]]"
   "About"  "back to [[Start]]"
   "Lonely" "nobody links here"})

(deftest facts-db-queries-match-pldb-semantics
  (let [dir (temp-wiki-dir corpus)
        page-store (pagestore/make-page-store (str dir))
        idx (index/build! (index/open-index) page-store)
        facts-db (index-db/make-facts-db idx)]
    (try
      (testing "all-pages is a sorted list of page names"
        (is (= ["About" "Lonely" "Start"] (vec (.all-pages facts-db)))))
      (testing "all-links is sorted [from to] pairs"
        (is (= [["About" "Start"] ["Start" "About"] ["Start" "Missing"]]
               (vec (.all-links facts-db)))))
      (testing "links-to returns [from target] pairs"
        (is (= [["About" "Start"]] (vec (.links-to facts-db "Start")))))
      (testing "links-to an unlinked page is empty (backlinks contract)"
        (is (= '() (.links-to facts-db "Lonely"))))
      (testing "broken-links are pairs whose target is not a page"
        (is (= [["Start" "Missing"]] (vec (.broken-links facts-db)))))
      (testing "orphan-pages have no inbound link"
        (is (= ["Lonely"] (vec (.orphan-pages facts-db)))))
      (testing "raw-db is a readable pages+links map"
        (is (= {:page ["About" "Lonely" "Start"]
                :link [["About" "Start"] ["Start" "About"] ["Start" "Missing"]]}
               (-> (.raw-db facts-db)
                   (update :page vec)
                   (update :link vec)))))
      (finally
        (index/close! idx)))))

(deftest facts-db-is-a-live-view-of-the-index
  (let [dir (temp-wiki-dir corpus)
        page-store (pagestore/make-page-store (str dir))
        idx (index/build! (index/open-index) page-store)
        facts-db (index-db/make-facts-db idx)]
    (try
      (spit (io/file dir "Missing.md") "now exists, links to [[Lonely]]")
      (index/index-page! idx page-store "Missing")
      (testing "the same facts-db instance sees the update"
        (is (= ["About" "Lonely" "Missing" "Start"] (vec (.all-pages facts-db))))
        (is (= '() (.broken-links facts-db)))
        (is (= [] (vec (.orphan-pages facts-db)))))
      (finally
        (index/close! idx)))))
