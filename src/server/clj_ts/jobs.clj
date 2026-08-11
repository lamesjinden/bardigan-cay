(ns clj-ts.jobs
  "The job mapping consumed by the JobServer middleware.

  Executor outputs are small data maps (they live in JobServer's
  in-memory history and flow through its JSON API); artifacts stay on
  disk in the artifact registry and are downloaded via their :url.

  NOTE: JobServer validates the mapping with spec/fspec, which
  generatively invokes every extract-args fn with random maps at
  startup -- extract-args must be pure and total."
  (:require [clj-ts.export.artifacts :as artifacts]
            [clj-ts.export.tiddlywiki :as tiddlywiki]))

(defn- export-all-pages [card-server-ref _args]
  (let [server-snapshot @card-server-ref
        result (tiddlywiki/export-wiki! server-snapshot)]
    (if (= :not-available result)
      (throw (ex-info "export is not available until the page database has been generated" {}))
      (let [{:keys [file failures]} result
            file-name (artifacts/sanitize-file-name (:wiki-name server-snapshot))
            artifact-id (artifacts/register! file file-name)]
        {:job-server/output {:url (str "/api/export/artifact/" artifact-id)
                             :file-name file-name
                             :size-bytes (.length file)
                             :failed-pages (mapv :page failures)}}))))

(defn create-job-mapping [card-server-ref]
  {:export/all-pages {:extract-args (fn [_params] {})
                      :executor (partial export-all-pages card-server-ref)}})
