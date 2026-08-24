(ns wiki.bc.storage.indexed-page-store-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wiki.bc.storage.index :as index]
            [wiki.bc.storage.indexed-page-store :as indexed-page-store]
            [wiki.bc.storage.page-store :as pagestore])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-wiki-dir [pages]
  (let [dir (-> (Files/createTempDirectory "bc-indexed-store-test" (make-array FileAttribute 0))
                (.toFile))]
    (.mkdirs (io/file dir "system"))
    (spit (io/file dir "system" "recentchanges") "")
    (doseq [[page-name source] pages]
      (spit (io/file dir (str page-name ".md")) source))
    dir))

(defn- make-store [dir]
  (let [file-store (pagestore/make-page-store (str dir))
        page-index (index/build! (index/open-index) file-store)]
    {:dir dir
     :page-index page-index
     :store (indexed-page-store/make-indexed-page-store page-index file-store)}))

(deftest reads-come-from-the-index
  (let [dir (temp-wiki-dir {"Start" "hello [[About]]"
                            "About" "about body"})
        {:keys [page-index store]} (make-store dir)]
    (try
      (is (= ["About" "Start"] (vec (.page-names store))))
      (is (.page-exists? store "Start"))
      (is (not (.page-exists? store "Missing")))
      (is (= "hello [[About]]" (.load-page store "Start")))
      (is (inst? (.last-modified store "Start")))
      (is (= ["about"] (map str/lower-case (.similar-page-names store "ABOUT"))))
      (testing "loading a page that is not indexed throws"
        (is (thrown? Exception (.load-page store "Missing"))))
      (testing "an external file edit is invisible until re-indexed"
        (spit (io/file dir "About.md") "edited outside the app")
        (is (= "about body" (.load-page store "About"))))
      (finally
        (index/close! page-index)))))

(deftest writes-go-to-file-and-index
  (let [dir (temp-wiki-dir {"Start" "hello"})
        {:keys [page-index store]} (make-store dir)]
    (try
      (.write-page! store "Start" "updated [[Elsewhere]]")
      (testing "the file is the durable copy"
        (is (= "updated [[Elsewhere]]" (slurp (io/file dir "Start.md")))))
      (testing "the index was updated in the same call"
        (is (= "updated [[Elsewhere]]" (.load-page store "Start"))))
      (testing "a page created by write-page! is fully indexed"
        (.write-page! store "Fresh" "brand new page")
        (is (.page-exists? store "Fresh"))
        (is (= "brand new page" (.load-page store "Fresh")))
        (is (.exists (io/file dir "Fresh.md"))))
      (finally
        (index/close! page-index)))))

(deftest write-page-to-file!-updates-recent-changes
  (let [dir (temp-wiki-dir {"Start" "hello"})
        {:keys [page-index store]} (make-store dir)
        snapshot {:page-store store}]
    (try
      (pagestore/write-page-to-file! snapshot "Start" "changed body")
      (is (= "changed body" (pagestore/read-page snapshot "Start")))
      (is (str/includes? (.read-recent-changes store) "[[Start]]"))
      (finally
        (index/close! page-index)))))
