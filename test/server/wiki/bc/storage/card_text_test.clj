(ns wiki.bc.storage.card-text-test
  (:require [clojure.test :refer [deftest is testing]]
            [wiki.bc.storage.card-text :as card-text]
            [wiki.bc.storage.index :as index]
            [wiki.bc.test-fixtures :refer [temp-wiki-dir build-index]]))

(deftest entries-are-write-once
  ;; the append-only, content-addressed contract every snapshot-holding
  ;; reader depends on: an existing hash is never overwritten, even by
  ;; a put carrying different text (unreachable when the hash is honest
  ;; -- it is a pure function of the text -- so this pins the guard,
  ;; not a real input)
  (let [dir (temp-wiki-dir {"P" "seed page"})
        idx (build-index dir)
        kv (:kv idx)
        h (str (random-uuid))]
    (try
      (card-text/put-new! kv [{:hash h :source_data "first text"}])
      (is (= "first text" (card-text/text kv h)))
      (card-text/put-new! kv [{:hash h :source_data "DIFFERENT text"}])
      (is (= "first text" (card-text/text kv h)))
      (testing "unknown hashes read nil"
        (is (nil? (card-text/text kv (str (random-uuid))))))
      (finally
        (index/close! idx)))))
