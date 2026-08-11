(ns clj-ts.export.artifacts-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clj-ts.export.artifacts :as artifacts])
  (:import [java.io File]))

(use-fixtures :each (fn [f]
                      (artifacts/reset-artifacts!)
                      (f)
                      (artifacts/reset-artifacts!)))

(defn- temp-artifact [content]
  (let [file (File/createTempFile "artifact-test-" ".html")]
    (spit file content)
    file))

(deftest register-and-lookup
  (let [file (temp-artifact "payload")
        id (artifacts/register! file "wiki.html")]
    (is (= {:file file :file-name "wiki.html"} (artifacts/lookup id)))
    (is (nil? (artifacts/lookup "no-such-id")))))

(deftest retention-keeps-the-newest-and-deletes-evicted-files
  (let [files (mapv (fn [i] (temp-artifact (str "artifact " i))) (range 12))
        ids (mapv (fn [file] (artifacts/register! file "wiki.html")) files)]
    (testing "the oldest two are evicted"
      (is (nil? (artifacts/lookup (nth ids 0))))
      (is (nil? (artifacts/lookup (nth ids 1))))
      (is (some? (artifacts/lookup (nth ids 2))))
      (is (some? (artifacts/lookup (nth ids 11)))))
    (testing "evicted files are deleted from disk, retained files are not"
      (is (not (.exists ^File (nth files 0))))
      (is (not (.exists ^File (nth files 1))))
      (is (.exists ^File (nth files 2)))
      (is (.exists ^File (nth files 11))))))

(deftest sanitize-file-name
  (is (= "My-Wiki.html" (artifacts/sanitize-file-name "My Wiki")))
  (is (= "wiki.html" (artifacts/sanitize-file-name "///")))
  (is (= "wiki.html" (artifacts/sanitize-file-name nil))))
