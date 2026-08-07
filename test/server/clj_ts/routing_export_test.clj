(ns clj-ts.routing-export-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-ts.card-server :as card-server]
            [clj-ts.routing :as routing]
            [clj-ts.storage.page-store :as pagestore])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-card-server [wiki-name pages]
  (let [dir (-> (Files/createTempDirectory "bc-routing-test" (make-array FileAttribute 0))
                (.toFile))]
    (.mkdirs (io/file dir "system"))
    (doseq [[page-name source] pages]
      (spit (io/file dir (str page-name ".md")) source))
    (let [page-store (pagestore/make-page-store (str dir))
          server-ref (card-server/create-card-server wiki-name "/" 4545 "Start" [] nil page-store)]
      (card-server/regenerate-db! server-ref)
      server-ref)))

(deftest export-all-pages-endpoint
  (let [server-ref (temp-card-server "My Test Wiki" {"Start" "# hello"})
        response (routing/export-all-pages-handler {:card-server server-ref})]
    (testing "responds with a self-contained html attachment"
      (is (= 200 (:status response)))
      (is (str/starts-with? (get-in response [:headers "Content-Type"]) "text/html"))
      (is (= "attachment; filename=\"My-Test-Wiki.html\""
             (get-in response [:headers "Content-Disposition"])))
      (is (str/includes? (:body response) "tiddlywiki-tiddler-store")))))

(deftest export-before-db-generation-is-unavailable
  (let [dir (-> (Files/createTempDirectory "bc-routing-test" (make-array FileAttribute 0))
                (.toFile))]
    (.mkdirs (io/file dir "system"))
    (let [page-store (pagestore/make-page-store (str dir))
          ;; no regenerate-db!: the facts db does not exist yet
          server-ref (card-server/create-card-server "w" "/" 4545 "Start" [] nil page-store)
          response (routing/export-all-pages-handler {:card-server server-ref})]
      (is (= 503 (:status response))))))
