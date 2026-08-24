(ns wiki.bc.jobs-test
  (:require [cljs.test :refer (deftest is)]
            [wiki.bc.jobs :as jobs]))

(deftest split-entries_partitions-active-and-history
  (let [running {:job-id "1" :status :running}
        accepted {:job-id "2" :status :accepted}
        success {:job-id "3" :status :success}
        failure {:job-id "4" :status :failure}
        local-a {:job-id "local-a" :status :failure :local? true}
        local-b {:job-id "local-b" :status :failure :local? true}
        {:keys [active history]} (jobs/split-entries {:entries [running accepted success failure]
                                                      :local-failures [local-a local-b]})]
    (is (= ["1" "2"] (map :job-id active)))
    (is (= ["local-b" "local-a" "3" "4"] (map :job-id history)))))

(deftest any-active?_detects-accepted-or-running
  (is (jobs/any-active? {:entries [{:status :success} {:status :accepted}]}))
  (is (jobs/any-active? {:entries [{:status :running}]}))
  (is (not (jobs/any-active? {:entries [{:status :success} {:status :failure}]})))
  (is (not (jobs/any-active? {:entries []}))))

(deftest format-size_scales-units
  (is (= "512 B" (jobs/format-size 512)))
  (is (= "1.5 KB" (jobs/format-size 1536)))
  (is (= "2.0 MB" (jobs/format-size 2097152)))
  (is (nil? (jobs/format-size nil))))

(deftest format-relative-time_produces-coarse-phrases
  (let [now (js/Date. 2026 7 23 12 0 0)]
    (is (= "just now" (jobs/format-relative-time "2026-08-23T11:59:30" now)))
    (is (= "5 min ago" (jobs/format-relative-time "2026-08-23T11:55:00" now)))
    (is (= "1 hr ago" (jobs/format-relative-time "2026-08-23T10:30:00" now)))
    (is (= "3 hrs ago" (jobs/format-relative-time "2026-08-23T09:00:00" now)))
    (is (= "yesterday" (jobs/format-relative-time "2026-08-22T10:00:00" now)))
    (is (= "3 days ago" (jobs/format-relative-time "2026-08-20T10:00:00" now)))))

(deftest format-relative-time_handles-fractional-seconds-from-localdatetime
  ;; JobServer stamps entries with (str (LocalDateTime/now)), which carries
  ;; nanosecond precision
  (let [now (js/Date. 2026 7 23 12 0 0)]
    (is (= "just now" (jobs/format-relative-time "2026-08-23T11:59:30.123456789" now)))))

(deftest format-relative-time_nil-for-missing-or-unparsable
  (let [now (js/Date. 2026 7 23 12 0 0)]
    (is (nil? (jobs/format-relative-time nil now)))
    (is (nil? (jobs/format-relative-time "" now)))
    (is (nil? (jobs/format-relative-time "not-a-date" now)))))

(deftest format-elapsed_formats-minutes-and-padded-seconds
  (let [now (js/Date. 2026 7 23 12 1 5)]
    (is (= "1:05" (jobs/format-elapsed "2026-08-23T12:00:00" now)))
    (is (= "0:00" (jobs/format-elapsed "2026-08-23T12:05:00" now)))
    (is (nil? (jobs/format-elapsed nil now)))))
