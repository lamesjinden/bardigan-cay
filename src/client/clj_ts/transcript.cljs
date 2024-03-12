(ns clj-ts.transcript
  (:require [clj-ts.mode :as mode]))

(def default-transcript "")
(def local-storage-key "transcript")

(defn get-initial-transcript []
  (or (js/localStorage.getItem local-storage-key) default-transcript))

(defn- updated-transcript [code result transcript]
  (str "<p> > " code "\n<br/>\n" result "\n</p>\n" transcript))

(defn prepend-transcript! [db code result]
  (let [current-transcript (-> @db :transcript)
        updated-transcript (updated-transcript code result current-transcript)]
    (swap! db assoc :transcript updated-transcript)
    (js/localStorage.setItem local-storage-key updated-transcript)))

(defn clear-transcript! [db]
  (swap! db assoc :transcript default-transcript)
  (js/localStorage.clear local-storage-key))

(defn exit-transcript! [db]
  (if (.-state js/history)
    (js/history.back)
    (mode/set-view-mode! db)))
