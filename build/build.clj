(ns build
  (:require [clojure.tools.build.api :as b]
            [inline])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(def artifact-name "clj-ts")
(def version "0.1.0-SNAPSHOT")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"
                            :aliases [:server]}))
(def uber-file
  (format "target/%s-%s-standalone.%s.jar"
          artifact-name
          version
          (.format (LocalDateTime/now)
                   (DateTimeFormatter/ofPattern "yyyy-MM-dd-HH-mm-ss"))))

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
           :main      'clj-ts.app}))