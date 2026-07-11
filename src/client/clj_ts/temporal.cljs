(ns clj-ts.temporal
  "Date/time functionality over plain js/Date, replacing the date-fns npm
   dependency. Functions follow date-fns semantics: arguments may be a
   js/Date, epoch milliseconds, or a date string; inputs are never mutated
   and results are new js/Date instances.

   Pattern-based parsing and formatting live in clj-ts.temporal.i18n so that
   the goog.i18n locale tables are only pulled into modules that need them."
  (:import [goog.date DateTime]))

(def ^:private ms-per-day 86400000)

(defn ->date
  "Coerce a js/Date, epoch milliseconds, or date string to a new js/Date.
   Like date-fns toDate: strings go through the js/Date constructor, so
   date-only strings are interpreted as UTC; use parse-iso for the local
   interpretation."
  [x]
  (cond
    (instance? js/Date x) (js/Date. (.getTime x))
    (or (number? x) (string? x)) (js/Date. x)
    :else (js/Date. js/NaN)))

;; region comparisons

(defn before? [a b]
  (< (.getTime (->date a)) (.getTime (->date b))))

(defn after? [a b]
  (> (.getTime (->date a)) (.getTime (->date b))))

(defn past? [d]
  (< (.getTime (->date d)) (js/Date.now)))

(defn same-day? [a b]
  (let [a (->date a)
        b (->date b)]
    (and (= (.getFullYear a) (.getFullYear b))
         (= (.getMonth a) (.getMonth b))
         (= (.getDate a) (.getDate b)))))

(defn today? [d]
  (same-day? d (js/Date.)))

(defn this-month? [d]
  (let [d (->date d)
        now (js/Date.)]
    (and (= (.getFullYear d) (.getFullYear now))
         (= (.getMonth d) (.getMonth now)))))

;; endregion

;; region arithmetic

(defn add-days [d n]
  (let [d (->date d)]
    (.setDate d (+ (.getDate d) n))
    d))

(defn sub-days [d n]
  (add-days d (- n)))

(defn add-hours [d n]
  (js/Date. (+ (.getTime (->date d)) (* n 3600000))))

(defn add-minutes [d n]
  (js/Date. (+ (.getTime (->date d)) (* n 60000))))

;; endregion

;; region calendar boundaries

(defn day-of-week
  "0 (Sunday) through 6 (Saturday), like date-fns getDay."
  [d]
  (.getDay (->date d)))

(defn weekend? [d]
  (contains? #{0 6} (day-of-week d)))

(defn start-of-day [d]
  (let [d (->date d)]
    (.setHours d 0 0 0 0)
    d))

(defn start-of-week
  "Local midnight of the Sunday beginning the week containing d
   (date-fns default weekStartsOn 0)."
  [d]
  (let [d (->date d)]
    (.setDate d (- (.getDate d) (.getDay d)))
    (.setHours d 0 0 0 0)
    d))

(defn this-week? [d]
  (= (.getTime (start-of-week d))
     (.getTime (start-of-week (js/Date.)))))

(defn start-of-month [d]
  (let [d (->date d)]
    (.setDate d 1)
    (.setHours d 0 0 0 0)
    d))

(defn end-of-month [d]
  (let [d (->date d)
        end (js/Date. (.getFullYear d) (inc (.getMonth d)) 0)]
    (.setHours end 23 59 59 999)
    end))

;; endregion

;; region differences

(defn- utc-day-number
  "Day index of the local calendar date, computed from UTC midnight so that
   day arithmetic is immune to DST transitions."
  [d]
  (/ (js/Date.UTC (.getFullYear d) (.getMonth d) (.getDate d)) ms-per-day))

(defn difference-in-calendar-days [a b]
  (- (utc-day-number (->date a)) (utc-day-number (->date b))))

(defn difference-in-days
  "Number of full days from b to a (sign of a minus b), like date-fns
   differenceInDays: partial trailing days are not counted."
  [a b]
  (let [a (->date a)
        b (->date b)
        sign (cond
               (< (.getTime a) (.getTime b)) -1
               (> (.getTime a) (.getTime b)) 1
               :else 0)
        calendar-days (js/Math.abs (difference-in-calendar-days a b))
        shifted (add-days a (* (- sign) calendar-days))
        partial-day? (cond
                       (pos? sign) (< (.getTime shifted) (.getTime b))
                       (neg? sign) (> (.getTime shifted) (.getTime b))
                       :else false)]
    (* sign (- calendar-days (if partial-day? 1 0)))))

(defn difference-in-seconds
  "Seconds from b to a, truncated toward zero like date-fns."
  [a b]
  (js/Math.trunc (/ (- (.getTime (->date a)) (.getTime (->date b))) 1000)))

;; endregion

;; region intervals and conversions

(defn each-day-of-interval
  "JS array of js/Date at local midnight for every calendar day from start
   to end inclusive; descending when start is after end, like date-fns."
  [start end]
  (let [start (->date start)
        end (->date end)
        reversed? (> (.getTime start) (.getTime end))
        [from until] (if reversed? [end start] [start end])
        until-time (.getTime until)]
    (loop [current (start-of-day from)
           days []]
      (if (<= (.getTime current) until-time)
        (recur (start-of-day (add-days current 1))
               (conj days current))
        (to-array (if reversed? (reverse days) days))))))

(defn hours->seconds [h]
  (js/Math.trunc (* h 3600)))

;; endregion

;; region ISO 8601

(defn parse-iso
  "Parse an ISO 8601 string. Date-only strings are interpreted as local
   midnight, matching date-fns parseISO rather than the js/Date constructor.
   Returns an invalid js/Date when parsing fails."
  [s]
  (if-let [parsed (.fromIsoString DateTime (str s))]
    (js/Date. (.getTime parsed))
    (js/Date. js/NaN)))

(defn- pad
  ([n] (pad n 2))
  ([n width] (.padStart (str n) width "0")))

(defn format-iso
  "Format as ISO 8601 in local time with numeric offset (or Z when UTC),
   matching date-fns formatISO: e.g. 2026-07-11T14:30:05+01:00."
  [d]
  (let [d (->date d)
        offset-minutes (.getTimezoneOffset d)
        offset (if (zero? offset-minutes)
                 "Z"
                 (let [total (js/Math.abs offset-minutes)
                       sign (if (pos? offset-minutes) "-" "+")]
                   (str sign (pad (quot total 60)) ":" (pad (rem total 60)))))]
    (str (pad (.getFullYear d) 4) "-" (pad (inc (.getMonth d))) "-" (pad (.getDate d))
         "T" (pad (.getHours d)) ":" (pad (.getMinutes d)) ":" (pad (.getSeconds d))
         offset)))

;; endregion
