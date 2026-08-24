(ns wiki.bc.jobs
  "Client-side jobs domain: view-model helpers over the [:jobs] app-db
   entries maintained by wiki.bc.jobs.jobs-process, plus entry/exit for
   the jobs view mode."
  (:require [clojure.string :as str]
            [wiki.bc.events.jobs :as e-jobs]
            [wiki.bc.mode :as mode]
            [wiki.bc.temporal :as temporal]))

(defn active-entry? [{:keys [status]}]
  (contains? #{:accepted :running} status))

(defn split-entries
  "Splits the jobs app-db value into {:active [...] :history [...]};
   local submission failures surface at the top of history."
  [{:keys [entries local-failures]}]
  {:active (filterv active-entry? entries)
   :history (vec (concat (reverse local-failures)
                         (remove active-entry? entries)))})

(defn any-active? [jobs]
  (boolean (some active-entry? (:entries jobs))))

(defn format-size
  "Human-readable size for a byte count; nil when the count is missing."
  [size-bytes]
  (when (number? size-bytes)
    (cond
      (< size-bytes 1024) (str size-bytes " B")
      (< size-bytes 1048576) (str (.toFixed (/ size-bytes 1024) 1) " KB")
      :else (str (.toFixed (/ size-bytes 1048576) 1) " MB"))))

(defn- valid-date? [d]
  (not (js/isNaN (.getTime d))))

(defn format-relative-time
  "Coarse 'time ago' phrase for an ISO timestamp relative to now;
   nil when the timestamp is missing or unparsable."
  [iso-string now]
  (when-not (str/blank? (str iso-string))
    (let [then (temporal/parse-iso iso-string)]
      (when (valid-date? then)
        (let [seconds (temporal/difference-in-seconds now then)
              minutes (js/Math.trunc (/ seconds 60))
              hours (js/Math.trunc (/ minutes 60))
              days (js/Math.trunc (/ hours 24))]
          (cond
            (< seconds 60) "just now"
            (< minutes 60) (str minutes " min ago")
            (< hours 24) (str hours (if (= 1 hours) " hr ago" " hrs ago"))
            (= 1 days) "yesterday"
            :else (str days " days ago")))))))

(defn format-elapsed
  "Elapsed m:ss between an ISO timestamp and now; nil when the timestamp
   is missing or unparsable."
  [iso-string now]
  (when-not (str/blank? (str iso-string))
    (let [then (temporal/parse-iso iso-string)]
      (when (valid-date? then)
        (let [seconds (max 0 (temporal/difference-in-seconds now then))
              minutes (js/Math.trunc (/ seconds 60))
              remainder (rem seconds 60)]
          (str minutes ":" (.padStart (str remainder) 2 "0")))))))

(defn enter-jobs-view!
  "Puts the app into jobs mode with fresh data and acknowledges any
   failure that was awaiting attention. History state is the caller's
   concern."
  [db]
  (mode/set-jobs-mode! db)
  (swap! db assoc-in [:jobs :attention?] false)
  (e-jobs/notify-jobs-refresh))

(defn exit-jobs-view! [db]
  (if (.-state js/history)
    (js/history.back)
    (mode/set-view-mode! db)))
