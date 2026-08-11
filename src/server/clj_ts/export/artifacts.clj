(ns clj-ts.export.artifacts
  "In-memory registry of export artifacts (files produced by completed
  jobs), addressed by generated id.

  Retention is owned here, independent of the jobs engine: only the most
  recent max-artifacts files are kept; registering beyond that deletes
  the oldest files. A lookup of an evicted id misses, which the API
  surfaces as 404 and the client can render as expired."
  (:require [clojure.string :as str])
  (:import [java.io File]))

(def ^:private max-artifacts 10)

;; {:order [id ...oldest-first] :by-id {id {:file ... :file-name ...}}}
(defonce ^:private artifacts* (atom {:order [] :by-id {}}))

(defn- evict [{:keys [order by-id] :as registry}]
  (if (<= (count order) max-artifacts)
    registry
    (let [evicted (first order)]
      {:order (vec (rest order))
       :by-id (dissoc by-id evicted)
       :evicted-file (get-in by-id [evicted :file])})))

(defn sanitize-file-name
  "A safe download name derived from the wiki name."
  [wiki-name]
  (let [base (-> (str wiki-name)
                 (str/replace #"[^A-Za-z0-9_-]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (str (if (str/blank? base) "wiki" base) ".html")))

(defn register!
  "Registers a file under a fresh artifact id; returns the id. May delete
  the oldest artifact's file to hold retention."
  [^File file file-name]
  (let [id (str (random-uuid))
        registered (swap! artifacts*
                          (fn [registry]
                            (-> registry
                                (dissoc :evicted-file)
                                (update :order conj id)
                                (assoc-in [:by-id id] {:file file
                                                       :file-name file-name})
                                (evict))))]
    (when-let [^File evicted-file (:evicted-file registered)]
      (.delete evicted-file))

    id))

(defn lookup
  "Returns {:file ... :file-name ...} or nil when unknown or evicted."
  [id]
  (get-in @artifacts* [:by-id id]))

(defn reset-artifacts!
  "Test helper: forgets all artifacts and deletes their files."
  []
  (let [[{:keys [by-id]} _] (reset-vals! artifacts* {:order [] :by-id {}})]
    (doseq [{:keys [^File file]} (vals by-id)]
      (.delete file))))
