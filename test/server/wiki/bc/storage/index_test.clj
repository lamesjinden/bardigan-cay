(ns wiki.bc.storage.index-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [wiki.bc.storage.index :as index]
            [wiki.bc.storage.page-store :as pagestore])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-wiki-dir [pages]
  (let [dir (-> (Files/createTempDirectory "bc-index-test" (make-array FileAttribute 0))
                (.toFile))]
    (.mkdirs (io/file dir "system"))
    (doseq [[page-name source] pages]
      (spit (io/file dir (str page-name ".md")) source))
    dir))

(defn- indexed-names [{:keys [conn]}]
  (sort (d/q '[:find [?n ...] :where [?e :page/name ?n]] (d/db conn))))

(defn- indexed-links [{:keys [conn]}]
  (d/q '[:find ?n ?t :where [?e :page/name ?n] [?e :page/links ?t]] (d/db conn)))

(defn- fulltext-names [idx query]
  (sort (index/search-pages idx query)))

(defn- build-index [dir]
  (index/build! (index/open-index) (pagestore/make-page-store (str dir))))

(deftest search-pages-is-token-based-and-ranked
  (let [dir (temp-wiki-dir {"Both" "the quick brown fox"
                            "One"  "quick notes about deadline: dates"
                            "None" "nothing relevant"})
        idx (build-index dir)]
    (try
      (testing "matching is case-insensitive"
        (is (= #{"Both" "One"} (set (index/search-pages idx "QUICK")))))
      (testing "a multi-term query ranks the page matching more terms first"
        (is (= #{"Both" "One"} (set (index/search-pages idx "quick fox"))))
        (is (= "Both" (first (index/search-pages idx "quick fox")))))
      (testing "punctuation around a token does not block a match"
        (is (= ["One"] (vec (index/search-pages idx "deadline")))))
      (testing "tokens match whole words only"
        (is (= [] (vec (index/search-pages idx "quic")))))
      (testing "blank and no-hit queries return empty"
        (is (= [] (vec (index/search-pages idx ""))))
        (is (= [] (vec (index/search-pages idx "zebra")))))
      (finally
        (index/close! idx)))))

(deftest search-pages-splits-camel-case
  (let [dir (temp-wiki-dir {"Linker" "see [[HelloWorld]] for details"
                            "Plain"  "hello there"})
        idx (build-index dir)]
    (try
      (testing "a word inside a CamelCase wiki link is searchable"
        (is (= #{"Linker" "Plain"} (set (index/search-pages idx "hello")))))
      (testing "the whole CamelCase token still matches exactly"
        (is (= ["Linker"] (vec (index/search-pages idx "helloworld")))))
      (testing "a CamelCase query ranks the exact-token page first"
        (is (= "Linker" (first (index/search-pages idx "HelloWorld")))))
      (finally
        (index/close! idx)))))

(deftest build-indexes-every-page
  (let [dir (temp-wiki-dir {"Start" "welcome to [[About]] and [[Missing]]"
                            "About" "plain body, no links"})
        idx (build-index dir)]
    (try
      (testing "all pages present"
        (is (= ["About" "Start"] (indexed-names idx))))
      (testing "links recorded, including targets that do not exist"
        (is (= #{["Start" "About"] ["Start" "Missing"]} (indexed-links idx))))
      (testing "full-text search finds body content"
        (is (= ["About"] (fulltext-names idx "plain")))
        (is (= ["Start"] (fulltext-names idx "welcome"))))
      (testing "last-modified is stored as an instant"
        (is (inst? (d/q '[:find ?m . :where [?e :page/name "Start"] [?e :page/last-modified ?m]]
                        (d/db (:conn idx))))))
      (finally
        (index/close! idx)))))

(deftest index-page!-replaces-rather-than-accumulates
  (let [dir (temp-wiki-dir {"Start" "first version links [[Old]]"})
        page-store (pagestore/make-page-store (str dir))
        idx (index/build! (index/open-index) page-store)]
    (try
      (spit (io/file dir "Start.md") "second version links [[New]]")
      (index/index-page! idx page-store "Start")
      (testing "old links are gone, new ones present"
        (is (= #{["Start" "New"]} (indexed-links idx))))
      (testing "full-text reflects the new body only"
        (is (= [] (fulltext-names idx "first")))
        (is (= ["Start"] (fulltext-names idx "second"))))
      (testing "still a single entity for the page"
        (is (= ["Start"] (indexed-names idx))))
      (finally
        (index/close! idx)))))

(deftest index-page!-indexes-a-page-created-after-build
  (let [dir (temp-wiki-dir {"Start" "hello"})
        page-store (pagestore/make-page-store (str dir))
        idx (index/build! (index/open-index) page-store)]
    (try
      (spit (io/file dir "Later.md") "created after startup, links [[Start]]")
      (index/index-page! idx page-store "Later")
      (is (= ["Later" "Start"] (indexed-names idx)))
      (is (= #{["Later" "Start"]} (indexed-links idx)))
      (finally
        (index/close! idx)))))

(deftest unindex-page!-removes-the-page
  (let [dir (temp-wiki-dir {"Start" "links [[About]]"
                            "About" "about body"})
        idx (build-index dir)]
    (try
      (index/unindex-page! idx "About")
      (is (= ["Start"] (indexed-names idx)))
      (testing "unindexing an unknown page is a no-op"
        (index/unindex-page! idx "NeverExisted")
        (is (= ["Start"] (indexed-names idx))))
      (finally
        (index/close! idx)))))

(deftest close!-removes-the-scratch-directory
  (let [dir (temp-wiki-dir {"Start" "hello"})
        idx (build-index dir)]
    (is (.exists (io/file (:dir idx))))
    (index/close! idx)
    (is (not (.exists (io/file (:dir idx)))))))
