(ns wiki.bc.storage.git-repo-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [wiki.bc.storage.git-repo :as git-repo]
            [wiki.bc.test-fixtures :refer [temp-git-wiki temp-wiki-dir write-page! commit-all!]]))

(deftest open-repo-finds-an-enclosing-repository
  (let [{:keys [root dir]} (temp-git-wiki {"Home" "hello"})
        repo (git-repo/open-repo (str dir))]
    (try
      (testing "a wiki nested below the root is found, with its prefix"
        (is (some? repo))
        (is (= "site/wiki/" (:page-prefix repo)))
        (is (string/starts-with? (git-repo/report repo) "true")))
      (testing "a wiki at the repository root has an empty prefix"
        (let [root-repo (git-repo/open-repo (str root))]
          (try
            (is (= "" (:page-prefix root-repo)))
            (finally
              (git-repo/close! root-repo)))))
      (testing "a repository with no commits has no history"
        (is (= [] (git-repo/page-revisions repo "Home")))
        (is (nil? (git-repo/resolve-commit repo "HEAD"))))
      (finally
        (git-repo/close! repo)))))

(deftest open-repo-is-nil-outside-a-repository
  (let [dir (temp-wiki-dir {"Home" "hello"})]
    (is (nil? (git-repo/open-repo (str dir))))
    (is (= "false" (git-repo/report nil)))))

(deftest history-and-content-at-a-revision
  (let [{:keys [root dir]} (temp-git-wiki {"Home" "first draft"
                                           "Other" "untouched"})
        first-sha (commit-all! root "add pages")
        _ (write-page! dir "Home" "second draft")
        second-sha (commit-all! root "edit home")
        _ (write-page! dir "Other" "other edited")
        third-sha (commit-all! root "edit other only")
        _ (write-page! dir "Home" "working tree edit, not committed")
        repo (git-repo/open-repo (str dir))]
    (try
      (testing "revisions are the commits touching the page, newest first"
        (let [revisions (git-repo/page-revisions repo "Home")]
          (is (= [second-sha first-sha] (mapv :sha revisions)))
          (is (= ["edit home" "add pages"] (mapv :message revisions)))
          (is (= [false false] (mapv :head? revisions)))
          (is (= "Tester" (-> revisions first :author)))
          (is (= 7 (-> revisions first :short_sha count)))))
      (testing "the page the last commit touched sees HEAD marked"
        (is (= [true false] (mapv :head? (git-repo/page-revisions repo "Other")))))
      (testing "a page git has never seen has no revisions"
        (is (= [] (git-repo/page-revisions repo "Nope"))))
      (testing "content is read from the commit, not the working tree"
        (is (= "first draft" (git-repo/page-body repo first-sha "Home")))
        (is (= "second draft" (git-repo/page-body repo second-sha "Home")))
        (is (= "second draft" (git-repo/page-body repo third-sha "Home"))))
      (testing "a page absent at a commit reads nil"
        (is (nil? (git-repo/page-body repo first-sha "Nope")))
        (is (false? (git-repo/page-exists? repo first-sha "Nope")))
        (is (true? (git-repo/page-exists? repo first-sha "Home"))))
      (testing "page names come from the commit's tree, pages only"
        (is (= ["Home" "Other"] (git-repo/page-names repo first-sha))))
      (testing "system files read from the commit; a missing one is nil"
        (is (= "" (git-repo/system-file repo first-sha "recentchanges")))
        (is (nil? (git-repo/system-file repo first-sha "nothing"))))
      (testing "revisions resolve from full, abbreviated and symbolic names"
        (is (= second-sha (git-repo/resolve-commit repo second-sha)))
        (is (= second-sha (git-repo/resolve-commit repo (subs second-sha 0 7))))
        (is (= third-sha (git-repo/resolve-commit repo "HEAD")))
        (is (nil? (git-repo/resolve-commit repo "no-such-revision")))
        (is (nil? (git-repo/resolve-commit repo "")))
        (is (nil? (git-repo/resolve-commit repo nil))))
      (testing "commit-info describes one commit"
        (let [info (git-repo/commit-info repo second-sha)]
          (is (= second-sha (:sha info)))
          (is (= "edit home" (:message info)))
          (is (some? (git-repo/commit-date repo second-sha)))))
      (finally
        (git-repo/close! repo)))))

(deftest history-follows-a-rename
  (let [{:keys [root dir]} (temp-git-wiki {"OldName" "a page with enough content to be recognised as the same file after a rename"})
        first-sha (commit-all! root "add page")
        _ (write-page! dir "OldName" nil)
        _ (write-page! dir "NewName" "a page with enough content to be recognised as the same file after a rename")
        second-sha (commit-all! root "rename page")
        repo (git-repo/open-repo (str dir))]
    (try
      (is (= [second-sha first-sha] (mapv :sha (git-repo/page-revisions repo "NewName"))))
      (finally
        (git-repo/close! repo)))))
