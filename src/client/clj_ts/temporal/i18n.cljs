(ns clj-ts.temporal.i18n
  "Pattern-based date parsing and formatting over goog.i18n, using LDML
   patterns (e.g. \"MM/dd\", \"EEE, LLL d, y, h:m aaa\") as date-fns does.
   Kept out of clj-ts.temporal so the goog.i18n formatter, parser, and
   locale symbol tables are only loaded by the workspace module."
  (:require [clj-ts.temporal :as temporal])
  (:import [goog.i18n DateTimeFormat DateTimeParse]))

(def ^:private pattern->formatter (memoize (fn [pattern] (DateTimeFormat. pattern))))
(def ^:private pattern->parser (memoize (fn [pattern] (DateTimeParse. pattern))))

(defn format
  "Format a date by an LDML pattern."
  [date pattern]
  (.format (pattern->formatter pattern) (temporal/->date date)))

(defn- reset-below-smallest-unit!
  "date-fns parse sets units below the smallest unit in the pattern to their
   minimum value, whereas goog.i18n keeps them from the date being filled.
   Align with date-fns by resetting them on the reference clone up front."
  [date pattern]
  (cond
    (re-find #"[sS]" pattern) (.setMilliseconds date 0)
    (re-find #"m" pattern) (.setSeconds date 0 0)
    (re-find #"[hHkK]" pattern) (.setMinutes date 0 0 0)
    (re-find #"[dDE]" pattern) (.setHours date 0 0 0 0)
    (re-find #"[ML]" pattern) (do (.setDate date 1)
                                  (.setHours date 0 0 0 0))
    (re-find #"y" pattern) (do (.setMonth date 0 1)
                               (.setHours date 0 0 0 0)))
  date)

(defn parse
  "Parse text by an LDML pattern, like date-fns parse: fields absent from
   the pattern are taken from reference-date, fields below the pattern's
   smallest unit are zeroed. Returns an invalid js/Date when parsing fails."
  [text pattern reference-date]
  (let [result (reset-below-smallest-unit! (temporal/->date reference-date) pattern)
        consumed (.parse (pattern->parser pattern) (str text) result)]
    (if (pos? consumed)
      result
      (js/Date. js/NaN))))
