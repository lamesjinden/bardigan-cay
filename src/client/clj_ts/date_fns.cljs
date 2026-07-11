(ns clj-ts.date-fns
  "Compatibility façade exposing the date-fns API surface used by workspace
   cards, backed by clj-ts.temporal instead of the date-fns npm package.
   Registered in the SCI environment under the \"date-fns\" js-lib key so
   existing (require '[\"date-fns\" :as date-fns]) forms keep working."
  (:require [goog.object :as gobj]
            [clj-ts.temporal :as temporal]
            [clj-ts.temporal.i18n :as i18n]))

(def module
  #js {:addDays temporal/add-days
       :addHours temporal/add-hours
       :addMinutes temporal/add-minutes
       :subDays temporal/sub-days
       :isAfter temporal/after?
       :isBefore temporal/before?
       :isPast temporal/past?
       :isToday temporal/today?
       :isThisWeek temporal/this-week?
       :isThisMonth temporal/this-month?
       :isWeekend temporal/weekend?
       :getDay temporal/day-of-week
       :startOfMonth temporal/start-of-month
       :endOfMonth temporal/end-of-month
       :differenceInDays temporal/difference-in-days
       :differenceInSeconds temporal/difference-in-seconds
       :eachDayOfInterval (fn [interval]
                            (temporal/each-day-of-interval
                             (gobj/get interval "start")
                             (gobj/get interval "end")))
       :hoursToSeconds temporal/hours->seconds
       :parseISO temporal/parse-iso
       :formatISO temporal/format-iso
       :parse i18n/parse
       :format i18n/format})
