(ns build
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [inline]))

(def artifact-name "bardigan-cay")
(def base-version "1.0.2")

(defn git-short-hash []
  (-> (p/process ["git" "rev-parse" "--short" "HEAD"] {:out :string})
      deref
      :out
      str/trim))

;; git-describe-style labeling: CI builds (Jenkins exports BUILD_NUMBER) are
;; "<base>-<build>-g<sha>"; interactive builds are "<base>-SNAPSHOT".
(def version
  (if-let [build-number (System/getenv "BUILD_NUMBER")]
    (format "%s-%s-g%s" base-version build-number (git-short-hash))
    (str base-version "-SNAPSHOT")))

(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"
                            :aliases [:server]}))
(def uber-file (format "target/%s-%s.jar" artifact-name version))

(defn uber [_]
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (inline/inline-assets class-dir)
  (b/compile-clj {:basis     basis
                  :src-dirs  ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     basis
           :main      'clj-ts.app
           :manifest  {"Implementation-Title"   artifact-name
                       "Implementation-Version" version
                       "Git-Commit"             (git-short-hash)
                       "Build-Timestamp"        (str (java.time.Instant/now))}}))