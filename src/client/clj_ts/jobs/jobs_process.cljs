(ns clj-ts.jobs.jobs-process
  "Maintains the client's view of server jobs under [:jobs] in the app db:

     {:entries [...]         ;; mirrored from the job-server state api
      :local-failures [...]} ;; submissions that never became server jobs

  Entry shape:
     {:job-id ... :label ... :status :accepted|:running|:success|:failure
      :accept-time ... :end-time ... :error-message ... :output {:url ...}}

  The process consumes submit commands and polls the job-server state api
  while any job is accepted or running (the api is poll-only)."
  (:require [cljs.core.async :as a]
            [clj-ts.http :as http]))

(def ^:private poll-interval-ms 2000)

(def ^:private job-type->label
  {"all-pages" "Export All Pages"})

(defn- server-record->entry [record status]
  (let [output (get record "executor-output")
        job-type (get record "job-type")]
    {:job-id (get record "job-id")
     :label (get job-type->label job-type job-type)
     :status status
     :accept-time (get record "accept-time")
     :end-time (get record "end-time")
     :error-message (get record "exception-message")
     :output (when output
               {:url (get output "url")
                :file-name (get output "file-name")
                :size-bytes (get output "size-bytes")
                :failed-pages (get output "failed-pages")})}))

(defn- pending->entry [record]
  (server-record->entry record
                        (if (get record "start-time")
                          :running
                          :accepted)))

(defn- history->entry [record]
  (server-record->entry record
                        (if (= "job/success" (get record "job-status"))
                          :success
                          :failure)))

(defn- state->entries
  "Active jobs first (newest submissions on top), then completed jobs
  newest-first."
  [state]
  (vec (concat (->> (get state "pending") (map pending->entry) (reverse))
               (->> (get state "history") (map history->entry) (reverse)))))

(defn- <fetch-state! [db]
  (a/go
    (let [response (a/<! (http/<http-get "/api/state"))]
      (when (:isSuccess response)
        (let [state (js->clj (js/JSON.parse (:body response)))]
          (swap! db assoc-in [:jobs :entries] (state->entries state)))))))

(defn- local-failure [label message]
  {:job-id (str "local-" (random-uuid))
   :label label
   :status :failure
   :error-message message
   :local? true})

(defn- <submit-job! [db {:keys [job-domain job-type label]}]
  (a/go
    (let [url (str "/api/jobs"
                   "?jobDomain=" (js/encodeURIComponent job-domain)
                   "&jobType=" (js/encodeURIComponent job-type))
          response (a/<! (http/<http-post url nil))]
      (if (and (:isSuccess response) (= 202 (:status response)))
        (a/<! (<fetch-state! db))
        (swap! db update-in [:jobs :local-failures] (fnil conj [])
               (local-failure label
                              (if (= 503 (:status response))
                                "server busy - too many queued jobs"
                                (str "submission failed (status " (:status response) ")"))))))))

(defn- active? [db]
  (->> (get-in @db [:jobs :entries])
       (some (fn [{:keys [status]}]
               (contains? #{:accepted :running} status)))))

(defn <create-jobs-process
  "Starts the jobs process. Rehydrates from the server on startup so the
  list survives page reloads, then serves submit commands and polls while
  jobs are in flight."
  [db jobs-cmd$]
  (a/go-loop [initialized? false]
    (if-not initialized?
      (do
        (a/<! (<fetch-state! db))
        (recur true))
      (let [channels (if (active? db)
                       [jobs-cmd$ (a/timeout poll-interval-ms)]
                       [jobs-cmd$])
            [value port] (a/alts! channels)]
        (if (and (= port jobs-cmd$) (nil? value))
          nil
          (do
            (if (= port jobs-cmd$)
              (condp = (:action value)
                :submit (a/<! (<submit-job! db value))
                (js/console.warn "unknown jobs command" (str value)))
              (a/<! (<fetch-state! db)))
            (recur true)))))))
