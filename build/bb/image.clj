(ns image
  "Build, load, and publish the bardigan-cay-build OCI toolchain image
   (nix/build-image.nix, exposed as packages.bardigan-cay-build). The same
   tasks serve developers and CI — see the bb tasks image-build, image-load,
   and image-publish."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn ->canonical-str [& args]
  (-> (apply fs/path args)
      (fs/canonicalize)
      (str)))

(def script-directory (-> *file*
                          (fs/parent)
                          (->canonical-str)))

(def repo-dir (-> script-directory
                  (fs/parent)
                  (fs/parent)
                  (->canonical-str)))

(def oci-image-name "bardigan-cay-build")

(def oci-image-tar
  "Path of the docker-archive tarball produced by `image-build`. Deliberately
   in the system temp directory rather than the repo: when the publish task
   runs in CI it executes as root with the workspace bind-mounted from the
   host, and root-owned artifacts in the workspace would break later checkouts."
  (str (fs/path (fs/temp-dir) (str oci-image-name ".tar"))))

(defn nix-build-oci-image
  "Build the bardigan-cay-build OCI image with nix. Returns the store path of
   the produced streaming script (streamLayeredImage output), which writes a
   docker-archive tarball to stdout when executed."
  []
  (-> (p/shell {:out :string :dir repo-dir}
               (format "nix build .#%s --no-link --print-out-paths" oci-image-name))
      :out
      str/trim
      str/split-lines
      last))

(defn build-oci-image
  "Build the bardigan-cay-build OCI image and stream it to a docker-archive
   tarball at `oci-image-tar`."
  []
  (let [stream-script (nix-build-oci-image)]
    (println "Streaming OCI image to" oci-image-tar)
    (p/shell {:out :write :out-file (fs/file oci-image-tar)} stream-script)))

(defn load-oci-image
  "Build the bardigan-cay-build OCI image and load it into the local Docker
   daemon (for interactive testing: `docker run -it bardigan-cay-build`)."
  []
  (let [stream-script (nix-build-oci-image)]
    (p/shell "bash" "-c" (str stream-script " | docker load"))))

(defn publish-oci-image
  "Publish the docker-archive tarball produced by `build-oci-image` to the
   container registry with skopeo, tagged `latest` and the current short git
   sha. Configured via environment variables (project-agnostic, shared by all
   projects publishing to the same Nexus):
     NEXUS_DOCKER_REGISTRY  registry host (default: docker.nexus.internal)
     NEXUS_DOCKER_USER      push username (required)
     NEXUS_DOCKER_PASS      push password (required)
   TLS trust is ambient: the system trust store, or SSL_CERT_FILE if set."
  []
  (let [registry (or (System/getenv "NEXUS_DOCKER_REGISTRY") "docker.nexus.internal")
        user (or (System/getenv "NEXUS_DOCKER_USER")
                 (throw (ex-info "NEXUS_DOCKER_USER must be set" {})))
        pass (or (System/getenv "NEXUS_DOCKER_PASS")
                 (throw (ex-info "NEXUS_DOCKER_PASS must be set" {})))
        sha (-> (p/shell {:out :string :dir repo-dir} "git rev-parse --short HEAD")
                :out
                str/trim)]
    (doseq [tag ["latest" sha]]
      (let [dest (format "docker://%s/%s:%s" registry oci-image-name tag)]
        (println "Publishing" dest)
        ;; --insecure-policy: we have no image-signing infrastructure, so skip
        ;; signature-policy enforcement. Without it skopeo refuses to run unless
        ;; a /etc/containers/policy.json exists on every machine (and in every
        ;; container image) this task runs in.
        (p/shell "skopeo" "copy"
                 "--insecure-policy"
                 "--dest-creds" (str user ":" pass)
                 (str "docker-archive:" oci-image-tar)
                 dest)))))
