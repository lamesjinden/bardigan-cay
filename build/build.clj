(ns build
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
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
  ;; inline-assets embeds the fonts into main.css and main.css into index.html,
  ;; so the standalone copies would ship the same bytes a second (and third) time
  (doseq [redundant ["public/css/main.css" "public/css/vendor"]]
    (b/delete {:path (str class-dir "/" redundant)}))
  ;; shadow-cljs emits source maps even for release builds; they only serve
  ;; browser devtools and have no place in the shipped artifact
  (doseq [file (.listFiles (io/file class-dir "public/js"))
          :when (str/ends-with? (.getName file) ".js.map")]
    (b/delete {:path (.getPath file)}))
  (b/compile-clj {:basis     basis
                  :src-dirs  ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     basis
           :main      'wiki.bc.app
           :manifest  {"Implementation-Title"   artifact-name
                       "Implementation-Version" version
                       "Git-Commit"             (git-short-hash)
                       "Build-Timestamp"        (str (java.time.Instant/now))}}))