(ns wiki.bc.storage.reconcile-test
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [wiki.bc.storage.index :as index]
            [wiki.bc.storage.page-store :as pagestore]
            [wiki.bc.storage.reconcile :as reconcile]
            [wiki.bc.test-fixtures :refer [temp-wiki-dir build-index await-search-sync!]]))

(deftest periodic-backstop-repairs-unhinted-content
  ;; the repair path for a DROPPED notification: content committed with
  ;; no hints ever delivered must become searchable on the interval's
  ;; full pass. Driven through a second process over the same stores at
  ;; interval 2; barrier-only events (each awaited before the next put)
  ;; make wakeups deterministic -- no coalescing, one wakeup per await.
  (let [dir (temp-wiki-dir {"P" "seed page"})
        store (pagestore/make-page-store (str dir))
        idx (build-index dir)
        my$ (a/chan 8)
        process$ (reconcile/create-reconcile-process my$ idx :full-pass-interval 2)]
    (try
      ;; commit content while suppressing every notification: neither
      ;; the index's own process nor ours hears about it
      (with-redefs [reconcile/notify! (fn [& _] nil)]
        (spit (io/file dir "Q.md") "unhinted capybara page")
        (index/index-page! idx store "Q"))
      (testing "wakeup 1 is a hinted pass and cannot know about the content"
        (is (reconcile/await! my$ 10000))
        (is (= [] (vec (index/search-pages idx "capybara")))))
      (testing "wakeup 2 is the interval's full backstop and repairs it"
        (is (reconcile/await! my$ 10000))
        (is (= ["Q"] (vec (index/search-pages idx "capybara")))))
      (finally
        (a/close! my$)
        (a/alts!! [process$ (a/timeout 10000)])
        (index/close! idx)))))

(deftest process-survives-a-failing-pass-and-backstops-its-lost-hints
  ;; the catch-and-continue contract plus the suspicious-pass policy: a
  ;; throwing pass is logged, the barrier still releases (out-chans
  ;; close even for failed passes), the process stays alive, and the
  ;; NEXT pass runs the full backstop derive -- so the failed pass's
  ;; lost add-hints are repaired even by a later write to an unrelated
  ;; page, whose own hints would never cover them
  (let [dir (temp-wiki-dir {"P" "original ibex text"})
        store (pagestore/make-page-store (str dir))
        idx (build-index dir)
        failing? (atom true)
        ;; inject below both the hinted and the full pass so the first
        ;; (hinted) pass fails and the repaired (full) pass succeeds
        real-add! @#'reconcile/add-missing-docs!]
    (try
      (with-redefs [reconcile/add-missing-docs!
                    (fn [stores hashes]
                      (if @failing?
                        (throw (ex-info "injected reconcile failure" {}))
                        (real-add! stores hashes)))]
        (testing "a failing pass is survived and the barrier still releases"
          (spit (io/file dir "P.md") "replacement takin text")
          (index/index-page! idx store "P")
          (is (await-search-sync! idx))
          (is (= [] (vec (index/search-pages idx "takin")))
              "the failed pass indexed nothing"))
        (reset! failing? false)
        (testing "an unrelated write triggers the full backstop, repairing
                  the failed pass's lost hints"
          (spit (io/file dir "Q.md") "unrelated quokka page")
          (index/index-page! idx store "Q")
          (is (await-search-sync! idx))
          (is (= ["P"] (vec (index/search-pages idx "takin"))))
          (is (= ["Q"] (vec (index/search-pages idx "quokka"))))
          (is (= [] (vec (index/search-pages idx "ibex"))))))
      (finally
        (index/close! idx)))))
