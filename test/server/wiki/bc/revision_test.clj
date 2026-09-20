(ns wiki.bc.revision-test
  "The revision views end to end: card-server resolvers over a git
  snapshot, and the routes that expose them."
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wiki.bc.card-server :as card-server]
            [wiki.bc.routing :as routing]
            [wiki.bc.storage.git-repo :as git-repo]
            [wiki.bc.storage.page-store :as pagestore]
            [wiki.bc.test-fixtures :refer [temp-git-wiki temp-wiki-dir write-page! commit-all!
                                           tracked-index close-tracked-indexes]]))

(use-fixtures :each close-tracked-indexes)

(def ^:private transcluding-source
  (str "the home page\n\n----\n"
       "{:card/type :transclude :from \"Source\" :ids [\"quote\"]}"))

(defn- card-texts [page]
  (mapv :server_prepared_data (:cards page)))

(defn- make-server [dir git-repo]
  (let [page-store (pagestore/make-page-store (str dir))
        page-index (tracked-index dir)]
    @(card-server/create-card-server "TestWiki" "/" 4545 "Home" [] page-index page-store git-repo)))

(deftest revision-page-renders-the-wiki-as-committed
  (let [{:keys [root dir]} (temp-git-wiki {"Home"   transcluding-source
                                           "Source" "{:card/type :markdown :card/id \"quote\"}\n\nold quote"})
        first-sha (commit-all! root "first")
        _ (write-page! dir "Source" "{:card/type :markdown :card/id \"quote\"}\n\nnew quote")
        _ (write-page! dir "Gone" "will be deleted")
        second-sha (commit-all! root "second")
        _ (write-page! dir "Gone" nil)
        _ (commit-all! root "third")
        repo (git-repo/open-repo (str dir))
        server (make-server dir repo)]
    (try
      (testing "the live page is offered the feature"
        (is (true? (:git_enabled (card-server/resolve-page server nil {:page_name "Home"} nil)))))
      (testing "the page's revisions come with the live page and the snapshot"
        (is (= [first-sha]
               (mapv :sha (:revisions (card-server/resolve-page server nil {:page_name "Home"} nil)))))
        (is (= [second-sha first-sha]
               (mapv :sha (:revisions (card-server/resolve-revision-page server "Source" first-sha))))))
      (testing "transclusions resolve against the same revision"
        (let [page (card-server/resolve-revision-page server "Home" first-sha)]
          (is (= first-sha (-> page :revision :sha)))
          (is (= "first" (-> page :revision :message)))
          (is (string/includes? (second (card-texts page)) "old quote"))
          (is (= [] (:system_cards page)))
          (is (true? (:git_enabled page))))
        (let [page (card-server/resolve-revision-page server "Home" second-sha)]
          (is (string/includes? (second (card-texts page)) "new quote"))))
      (testing "the live render transcludes the working tree"
        (is (string/includes? (second (card-texts (card-server/resolve-page server nil {:page_name "Home"} nil)))
                              "new quote")))
      (testing "a page absent at the revision says so instead of inviting an edit"
        (let [page (card-server/resolve-revision-page server "Gone" first-sha)]
          (is (string/includes? (first (card-texts page)) "DID NOT EXIST AT REVISION"))
          (is (= "" (:body (card-server/resolve-revision-source-page server "Gone" first-sha)))))
        (is (= "will be deleted"
               (:body (card-server/resolve-revision-source-page server "Gone" second-sha)))))
      (testing "revisions resolve through the server"
        (is (= first-sha (card-server/resolve-commit server (subs first-sha 0 8))))
        (is (nil? (card-server/resolve-commit server "bogus"))))
      (finally
        (git-repo/close! repo)))))

(deftest outside-a-repository-the-feature-is-absent
  (let [dir (temp-wiki-dir {"Home" "hello"})
        server (make-server dir nil)]
    (is (false? (:git_enabled (card-server/resolve-page server nil {:page_name "Home"} nil))))
    (is (= [] (:revisions (card-server/resolve-page server nil {:page_name "Home"} nil))))
    (is (false? (card-server/git-enabled? server)))))

(defn- request [server uri params]
  (routing/request-handler {:uri            uri
                            :request-method :get
                            :params         params
                            :card-server    (atom server)}))

(defn- json-body [response]
  (json/read-str (:body response) :key-fn keyword))

(deftest routes-serve-revisions
  (let [{:keys [root dir]} (temp-git-wiki {"Home" "first draft"})
        first-sha (commit-all! root "first")
        _ (write-page! dir "Home" "second draft")
        _ (commit-all! root "second")
        repo (git-repo/open-repo (str dir))
        server (make-server dir repo)]
    (try
      (testing "the revision list rides along with the page"
        (let [response (request server "/api/page/Home" {})]
          (is (= 200 (:status response)))
          (is (= 2 (count (-> (json-body response) :server_prepared_page :revisions))))))
      (testing "the page at a revision, through the page route"
        (let [response (request server "/api/page/Home" {:rev (subs first-sha 0 7)})
              body (json-body response)]
          (is (= 200 (:status response)))
          (is (= "first draft" (-> body :source_page :body)))
          (is (= first-sha (-> body :server_prepared_page :revision :sha)))))
      (testing "the live page has no revision"
        (let [body (json-body (request server "/api/page/Home" {}))]
          (is (= "second draft" (-> body :source_page :body)))
          (is (nil? (-> body :server_prepared_page :revision)))))
      (testing "an unknown revision is not found"
        (is (= 404 (:status (request server "/api/page/Home" {:rev "nothing"})))))
      (testing "the html page route renders a revision and rejects an unknown one"
        (let [response (request server "/pages/Home" {:rev first-sha})]
          (is (= 200 (:status response)))
          (is (string/includes? (:body response) first-sha)))
        (is (= 404 (:status (request server "/pages/Home" {:rev "nothing"}))))
        (is (= 404 (:status (request server "/pages/Missing" {}))))
        ;; a page missing at the revision still renders, as a snapshot page
        (is (= 200 (:status (request server "/pages/Missing" {:rev first-sha})))))
      (finally
        (git-repo/close! repo)))))

(deftest routes-hide-revisions-outside-a-repository
  (let [dir (temp-wiki-dir {"Home" "hello"})
        server (make-server dir nil)]
    (is (= 404 (:status (request server "/api/page/Home" {:rev "HEAD"}))))
    (let [response (request server "/api/page/Home" {})]
      (is (= 200 (:status response)))
      (is (= [] (-> (json-body response) :server_prepared_page :revisions))))))
