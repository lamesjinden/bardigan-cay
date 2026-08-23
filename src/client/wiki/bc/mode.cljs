(ns wiki.bc.mode)

(defn set-edit-mode! [db]
  (swap! db assoc :mode :editing))

(defn set-view-mode! [db]
  (swap! db assoc :mode :viewing))

(defn set-transcript-mode! [db]
  (swap! db assoc :mode :transcript))

(defn editing? [db]
  (= :editing (:mode @db)))

(defn viewing? [db-or-mode]
  (let [value @db-or-mode]
    (or (= :viewing value)
        (= :viewing (:mode value)))))