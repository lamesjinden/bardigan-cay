(ns clj-ts.temporal-test
  (:require [cljs.test :refer (deftest is)]
            [goog.object :as gobj]
            [sci.core :as sci]
            [clj-ts.temporal :as temporal]
            [clj-ts.temporal.i18n :as i18n]
            [clj-ts.date-fns :as date-fns]))

;; region temporal

(deftest add-days_adds-across-month-boundary-without-mutating
  (let [d (js/Date. 2026 0 30 12 0 0 0)
        d' (temporal/add-days d 3)]
    (is (= 1 (.getMonth d')))
    (is (= 2 (.getDate d')))
    (is (= 12 (.getHours d')))
    (is (= 0 (.getMonth d)))
    (is (= 30 (.getDate d)))))

(deftest sub-days_subtracts
  (let [d' (temporal/sub-days (js/Date. 2026 0 2) 3)]
    (is (= 2025 (.getFullYear d')))
    (is (= 11 (.getMonth d')))
    (is (= 30 (.getDate d')))))

(deftest add-hours-and-minutes_are-millisecond-based
  (let [d (js/Date. 2026 6 11 10 15 0 0)]
    (is (= 13 (.getHours (temporal/add-hours d 3))))
    (is (= 45 (.getMinutes (temporal/add-minutes d 30))))
    (is (= 10 (.getHours d)))))

(deftest comparisons_follow-date-fns-semantics
  (let [earlier (js/Date. 2026 6 11 10 0)
        later (js/Date. 2026 6 11 11 0)]
    (is (temporal/before? earlier later))
    (is (not (temporal/before? later earlier)))
    (is (temporal/after? later earlier))
    (is (temporal/past? (temporal/sub-days (js/Date.) 1)))
    (is (not (temporal/past? (temporal/add-days (js/Date.) 1))))))

(deftest now-relative-predicates
  (let [now (js/Date.)]
    (is (temporal/today? now))
    (is (not (temporal/today? (temporal/add-days now 1))))
    (is (temporal/this-week? now))
    (is (not (temporal/this-week? (temporal/add-days now 14))))
    (is (temporal/this-month? now))
    (is (not (temporal/this-month? (temporal/add-days now 40))))))

(deftest day-of-week_and-weekend
  (is (= 6 (temporal/day-of-week (js/Date. 2025 0 4))))
  (is (= 0 (temporal/day-of-week (js/Date. 2025 0 5))))
  (is (= 1 (temporal/day-of-week (js/Date. 2025 0 6))))
  (is (temporal/weekend? (js/Date. 2025 0 4)))
  (is (temporal/weekend? (js/Date. 2025 0 5)))
  (is (not (temporal/weekend? (js/Date. 2025 0 6)))))

(deftest start-of-week_returns-sunday-midnight
  (let [d' (temporal/start-of-week (js/Date. 2025 0 8 13 30))]
    (is (= 5 (.getDate d')))
    (is (= 0 (.getDay d')))
    (is (= 0 (.getHours d')))))

(deftest month-boundaries
  (let [start (temporal/start-of-month (js/Date. 2026 1 15 13 30 45 500))
        end (temporal/end-of-month (js/Date. 2026 1 15 13 30 45 500))]
    (is (= [2026 1 1 0 0 0 0]
           [(.getFullYear start) (.getMonth start) (.getDate start)
            (.getHours start) (.getMinutes start) (.getSeconds start) (.getMilliseconds start)]))
    (is (= [2026 1 28 23 59 59 999]
           [(.getFullYear end) (.getMonth end) (.getDate end)
            (.getHours end) (.getMinutes end) (.getSeconds end) (.getMilliseconds end)]))))

(deftest difference-in-days_counts-full-days-only
  (let [earlier (js/Date. 2026 6 11 23 59)
        later (js/Date. 2026 6 13 0 1)]
    (is (= 2 (temporal/difference-in-calendar-days later earlier)))
    (is (= 1 (temporal/difference-in-days later earlier)))
    (is (= -1 (temporal/difference-in-days earlier later)))
    (is (= 2 (temporal/difference-in-days (js/Date. 2026 6 13 23 59) earlier)))
    (is (= 0 (temporal/difference-in-days earlier earlier)))))

(deftest difference-in-seconds_accepts-iso-strings
  ;; the Workspace page passes ISO strings directly, as date-fns v3 allowed
  (is (= 638 (temporal/difference-in-seconds "2025-01-01T17:43:47Z" "2025-01-01T17:33:09Z")))
  (is (= -638 (temporal/difference-in-seconds "2025-01-01T17:33:09Z" "2025-01-01T17:43:47Z"))))

(deftest each-day-of-interval_returns-inclusive-midnights
  (let [days (temporal/each-day-of-interval (js/Date. 2026 5 28 15 30)
                                            (js/Date. 2026 6 4 9 0))
        first-day (aget days 0)
        last-day (aget days (dec (alength days)))]
    (is (= 7 (alength days)))
    (is (some? (seq days)))
    (is (= [5 28 0] [(.getMonth first-day) (.getDate first-day) (.getHours first-day)]))
    (is (= [6 4 0] [(.getMonth last-day) (.getDate last-day) (.getHours last-day)]))))

(deftest hours->seconds_converts
  (is (= 86400 (temporal/hours->seconds 24))))

(deftest parse-iso_treats-date-only-as-local-midnight
  (let [d (temporal/parse-iso "2025-03-10")]
    (is (= [2025 2 10 0 0] [(.getFullYear d) (.getMonth d) (.getDate d) (.getHours d) (.getMinutes d)]))))

(deftest parse-iso_handles-utc-and-offsets
  (is (= (js/Date.UTC 2025 0 1 12) (.getTime (temporal/parse-iso "2025-01-01T12:00:00Z"))))
  (is (= (js/Date.UTC 2025 0 1 10) (.getTime (temporal/parse-iso "2025-01-01T12:00:00+02:00"))))
  (is (js/isNaN (.getTime (temporal/parse-iso "not-a-date")))))

(deftest format-iso_is-local-time-with-offset
  (let [d (js/Date. 2026 6 11 14 30 5 0)
        formatted (temporal/format-iso d)]
    (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:\d{2})" formatted))
    (is (= "2026-07-11T14:30:05" (subs formatted 0 19)))
    (is (= (.getTime d) (.getTime (temporal/parse-iso formatted))))))

;; endregion

;; region i18n

(deftest format_formats-by-ldml-pattern
  (is (= "07/11" (i18n/format (js/Date. 2026 6 11) "MM/dd")))
  (is (= "2026-07-11" (i18n/format (js/Date. 2026 6 11) "yyyy-MM-dd"))))

(deftest parse_month-day-pattern-takes-year-from-reference-and-zeroes-time
  (let [reference (js/Date. 2026 3 5 16 45 30 500)
        d (i18n/parse "07/11" "MM/dd" reference)]
    (is (= [2026 6 11 0 0 0 0]
           [(.getFullYear d) (.getMonth d) (.getDate d)
            (.getHours d) (.getMinutes d) (.getSeconds d) (.getMilliseconds d)]))
    ;; the reference date is not mutated
    (is (= 3 (.getMonth reference)))))

(deftest parse_time-pattern-takes-date-from-reference-and-zeroes-seconds
  (let [d (i18n/parse "09:30" "HH:mm" (js/Date. 2026 6 11 22 15 45 999))]
    (is (= [2026 6 11 9 30 0 0]
           [(.getFullYear d) (.getMonth d) (.getDate d)
            (.getHours d) (.getMinutes d) (.getSeconds d) (.getMilliseconds d)]))))

(deftest parse_gmail-pattern-round-trips
  ;; the pattern used by the DoorDash page for Gmail-style dates
  (let [d (i18n/parse "Mon, Jan 6, 2025, 3:45 PM" "EEE, LLL d, y, h:m aaa" (js/Date. 2026 0 1 8 9 10 11))]
    (is (= [2025 0 6 15 45 0 0]
           [(.getFullYear d) (.getMonth d) (.getDate d)
            (.getHours d) (.getMinutes d) (.getSeconds d) (.getMilliseconds d)]))))

(deftest parse_failure-returns-invalid-date
  (is (js/isNaN (.getTime (i18n/parse "garbage" "MM/dd" (js/Date.))))))

;; endregion

;; region date-fns façade

(deftest module_exposes-the-full-documented-surface
  (doseq [name ["addDays" "addHours" "addMinutes" "subDays"
                "isAfter" "isBefore" "isPast" "isToday" "isThisWeek" "isThisMonth" "isWeekend"
                "getDay" "startOfMonth" "endOfMonth"
                "differenceInDays" "differenceInSeconds" "eachDayOfInterval"
                "hoursToSeconds" "parseISO" "formatISO" "parse" "format"]]
    (is (fn? (gobj/get date-fns/module name)) name)))

(deftest module_functions-work-as-called-from-workspace-cards
  (let [each-day-of-interval (gobj/get date-fns/module "eachDayOfInterval")
        difference-in-seconds (gobj/get date-fns/module "differenceInSeconds")
        hours-to-seconds (gobj/get date-fns/module "hoursToSeconds")
        days (each-day-of-interval #js {"start" (js/Date. 2026 6 1) "end" (js/Date. 2026 6 3)})]
    (is (= 3 (count (seq days))))
    (is (= 638 (difference-in-seconds "2025-01-01T17:43:47Z" "2025-01-01T17:33:09Z")))
    (is (= 86400 (hours-to-seconds 24)))))

(deftest module_resolves-through-sci-js-libs-require
  ;; the exact pathway workspace cards use: (require '["date-fns" :as date-fns])
  (let [opts (sci/init {:classes {'js js/globalThis :allow :all}
                        :js-libs {"date-fns" date-fns/module}})]
    (is (= 86400 (sci/eval-string* opts "(require '[\"date-fns\" :as date-fns])
                                         (date-fns/hoursToSeconds 24)")))
    (is (= "07/11" (sci/eval-string* opts "(require '[\"date-fns\" :as date-fns])
                                           (date-fns/format (js/Date. 2026 6 11) \"MM/dd\")")))
    (is (= 638 (sci/eval-string* opts "(require '[\"date-fns\" :as date-fns])
                                       (date-fns/differenceInSeconds \"2025-01-01T17:43:47Z\" \"2025-01-01T17:33:09Z\")")))))

;; endregion
