(ns build
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [inline]))

(def artifact-name "bardigan-cay")
(def version "1.0.0")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"
                            :aliases [:server]}))
(def uber-file (format "target/%s-%s.jar" artifact-name version))

(defn git-short-hash []
  (-> (p/process ["git" "rev-parse" "--short" "HEAD"] {:out :string})
      deref
      :out
      str/trim))

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