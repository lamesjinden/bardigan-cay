(ns clj-ts.jobs-test
  "Integration test of the async export job flow through the full ring
  pipeline: submit via POST /api/jobs, observe completion via the
  job-server state api, download the artifact from the output url."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clj-ts.card-server :as card-server]
            [clj-ts.export.artifacts :as artifacts]
            [clj-ts.server :as server]
            [clj-ts.storage.page-store :as pagestore])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :each (fn [f]
                      (artifacts/reset-artifacts!)
                      (f)
                      (artifacts/reset-artifacts!)))

(defn- temp-pipeline []
  (let [dir (-> (Files/createTempDirectory "bc-jobs-test" (make-array FileAttribute 0))
                (.toFile))]
    (.mkdirs (io/file dir "system"))
    (spit (io/file dir "Start.md") "# hello jobs")
    (let [page-store (pagestore/make-page-store (str dir))
          server-ref (card-server/create-card-server "Jobs Test Wiki" "/" 4545 "Start" [] nil page-store)]
      (card-server/regenerate-db! server-ref)
      (server/create-request-pipeline server-ref))))

(defn- request
  ([pipeline method uri] (request pipeline method uri nil))
  ([pipeline method uri query-string]
   (pipeline (cond-> {:request-method method
                      :uri uri
                      :scheme :http
                      :server-name "localhost"
                      :server-port 4545
                      :headers {}}
               query-string (assoc :query-string query-string)))))

(defn- json-body [response]
  (json/read-str (:body response)))

(defn- await-history
  "Polls the job-server state api until history is non-empty (or times out);
  returns the history vector."
  [pipeline]
  (loop [attempt 0]
    (let [history (get (json-body (request pipeline :get "/api/state")) "history")]
      (cond
        (seq history) history
        (< attempt 100) (do (Thread/sleep 100) (recur (inc attempt)))
        :else (throw (ex-info "timed out waiting for job completion" {}))))))

(deftest export-job-round-trip
  (let [pipeline (temp-pipeline)
        submit-response (request pipeline :post "/api/jobs"
                                 "jobDomain=export&jobType=all-pages")]
    (testing "submission is accepted with job urls"
      (is (= 202 (:status submit-response)))
      (is (some? (get (json-body submit-response) "jobId")))
      (is (str/includes? (get-in submit-response [:headers "Content-Location"]) "/status")))
    (let [history (await-history pipeline)
          job (first history)]
      (testing "the job succeeds with a url-bearing output"
        (is (= "job/success" (get job "job-status")))
        (let [output (get job "executor-output")]
          (is (str/starts-with? (get output "url") "/api/export/artifact/"))
          (is (= "Jobs-Test-Wiki.html" (get output "file-name")))
          (is (pos? (get output "size-bytes")))
          (is (= [] (get output "failed-pages")))
          (testing "the artifact url downloads the export"
            (let [download (request pipeline :get (get output "url"))]
              (is (= 200 (:status download)))
              (is (= "attachment; filename=\"Jobs-Test-Wiki.html\""
                     (get-in download [:headers "Content-Disposition"])))
              (is (instance? File (:body download)))
              (is (str/includes? (slurp (:body download)) "tiddlywiki-tiddler-store")))))))))

(deftest unknown-artifact-is-not-found
  (let [pipeline (temp-pipeline)
        response (request pipeline :get (str "/api/export/artifact/" (random-uuid)))]
    (is (= 404 (:status response)))))

(deftest job-status-endpoint-tracks-lifecycle
  (let [pipeline (temp-pipeline)
        submit-response (request pipeline :post "/api/jobs"
                                 "jobDomain=export&jobType=all-pages")
        job-id (get (json-body submit-response) "jobId")]
    (await-history pipeline)
    (let [status (json-body (request pipeline :get (str "/api/job/" job-id "/status")))]
      (is (= "job/success" (get status "job-status")))
      (is (some? (get status "accept-time")))
      (is (some? (get status "end-time"))))))
