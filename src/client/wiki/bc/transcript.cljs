(ns wiki.bc.transcript
  (:require [cljs.reader :as reader]
            [clojure.walk :as walk]
            [reagent.core :as r]
            [reagent.dom.server :as server]
            [wiki.bc.mode :as mode]))

(def default-transcript [])
(def local-storage-key "transcript")

(defn get-initial-transcript []
  (try
    (let [parsed (some-> (js/localStorage.getItem local-storage-key)
                         (reader/read-string))]
      (if (vector? parsed)
        parsed
        default-transcript))
    (catch :default _
      default-transcript)))

(defn hydrate-entry
  "Entries are stored as pure EDN, so raw-html nodes carry a plain {:__html s} map;
   reagent only honours :dangerouslySetInnerHTML when the value is its UnsafeHTML
   type, so wrap those maps before rendering."
  [entry]
  (walk/postwalk
   (fn [x]
     (if (and (map? x)
              (= 1 (count x))
              (string? (:__html x)))
       (r/unsafe-html (:__html x))
       x))
   entry))

(defn transcript->html [transcript]
  (->> transcript
       (map #(server/render-to-static-markup (hydrate-entry %)))
       (apply str)))

(defn- transcript-entry [code result]
  [:p " > " code [:br] result])

(defn prepend-transcript! [db code result]
  (let [updated-transcript (into [(transcript-entry code result)]
                                 (:transcript @db))]
    (swap! db assoc :transcript updated-transcript)
    (js/localStorage.setItem local-storage-key (pr-str updated-transcript))))

(defn clear-transcript! [db]
  (swap! db assoc :transcript default-transcript)
  (js/localStorage.removeItem local-storage-key))

(defn exit-transcript! [db]
  (if (.-state js/history)
    (js/history.back)
    (mode/set-view-mode! db)))
