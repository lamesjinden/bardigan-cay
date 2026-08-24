(ns wiki.bc.search-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [wiki.bc.card-server :as card-server]
            [wiki.bc.storage.index :as index]
            [wiki.bc.storage.page-store :as pagestore])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-wiki-dir [pages]
  (let [dir (-> (Files/createTempDirectory "bc-search-test" (make-array FileAttribute 0))
                (.toFile))]
    (.mkdirs (io/file dir "system"))
    (spit (io/file dir "system" "recentchanges") "")
    (doseq [[page-name source] pages]
      (spit (io/file dir (str page-name ".md")) source))
    dir))

(deftest resolve-text-search-combines-name-and-fulltext-matches
  (let [dir (temp-wiki-dir {"CheeseShop" "we sell dairy products"
                            "Diary"      "cheese is mentioned here"
                            "Menu"       "our supplier is [[CheeseShop]]"
                            "Other"      "nothing relevant"})
        page-store (pagestore/make-page-store (str dir))
        page-index (index/build! (index/open-index) page-store)
        server-ref (card-server/create-card-server "TestWiki" "/" 4545 "Start" [] page-index page-store)]
    (try
      (let [{:keys [name_matches text_matches]}
            (card-server/resolve-text-search @server-ref nil {:query_string "cheese"} nil)]
        (testing "page names match by case-insensitive substring"
          (is (= ["CheeseShop"] (vec name_matches))))
        (testing "page text matches through the full-text engine, including
                  words inside CamelCase wiki links"
          (is (= #{"Diary" "Menu"} (set text_matches)))))
      (testing "a blank query matches nothing"
        (let [{:keys [name_matches text_matches]}
              (card-server/resolve-text-search @server-ref nil {:query_string "  "} nil)]
          (is (= [] (vec name_matches)))
          (is (= [] (vec text_matches)))))
      (testing "a page written through the card-server is searchable immediately"
        (card-server/write-page-to-file! server-ref "Fresh" "entirely unique zanzibar content")
        (is (= ["Fresh"]
               (-> (card-server/resolve-text-search @server-ref nil {:query_string "zanzibar"} nil)
                   :text_matches
                   vec))))
      (finally
        (index/close! page-index)))))
