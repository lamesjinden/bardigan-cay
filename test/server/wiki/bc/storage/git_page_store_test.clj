(ns wiki.bc.storage.git-page-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [wiki.bc.storage.git-page-store :as git-page-store]
            [wiki.bc.storage.git-repo :as git-repo]
            [wiki.bc.storage.page-store :as pagestore]
            [wiki.bc.test-fixtures :refer [temp-git-wiki write-page! commit-all!]]))

(def ^:private source
  (str "intro card"
       "\n\n----\n"
       "{:card/type :markdown :card/id \"alpha\"}\n\nsecond card"))

(deftest reads-come-from-the-commit-and-writes-are-refused
  (let [{:keys [root dir]} (temp-git-wiki {"Multi" source})
        sha (commit-all! root "add")
        _ (write-page! dir "Multi" "changed after the commit")
        _ (write-page! dir "Later" "added after the commit")
        repo (git-repo/open-repo (str dir))
        file-store (pagestore/make-page-store (str dir))
        store (git-page-store/make-git-page-store repo sha file-store)]
    (try
      (testing "pages and bodies are those of the commit"
        (is (= ["Multi"] (.page-names store)))
        (is (true? (.page-exists? store "Multi")))
        (is (false? (.page-exists? store "Later")))
        (is (= source (.load-page store "Multi"))))
      (testing "a page absent at the commit throws, like the indexed store"
        (is (thrown? clojure.lang.ExceptionInfo (.load-page store "Later"))))
      (testing "cards are parsed from the committed body"
        (is (= 2 (count (.get-page-as-card-maps store "Multi"))))
        (is (= "intro card" (:source_data (.get-card store "Multi" (:hash (first (.get-page-as-card-maps store "Multi")))))))
        (is (= 1 (count (.get-cards-from-page store "Multi" ["alpha"])))))
      (testing "system files read from the commit"
        (is (= "" (.read-recent-changes store)))
        (is (= "" (.read-system-file store "nothing"))))
      (testing "the description names the revision"
        (is (= sha (:revision (.as-map store))))
        (is (some? (.last-modified store "Multi"))))
      (testing "every write is refused"
        (is (thrown? clojure.lang.ExceptionInfo (.write-page! store "Multi" "x")))
        (is (thrown? clojure.lang.ExceptionInfo (.write-page-delta! store "Multi" "x" {})))
        (is (thrown? clojure.lang.ExceptionInfo (.write-system-file! store "f" "x")))
        (is (thrown? clojure.lang.ExceptionInfo (.write-recent-changes! store "x")))
        (is (nil? (.refresh-page! store "Multi"))))
      (testing "media is the live store's"
        (is (= (.media-list file-store) (.media-list store))))
      (finally
        (git-repo/close! repo)))))
