(ns wiki.bc.page
  (:require [cljs.core.async :as a]
            [wiki.bc.events.saving :as e-saving]
            [wiki.bc.navigation :as nav]))

(defn enter-view-mode! [db]
  (swap! db assoc :mode :viewing))

(defn enter-edit-mode! [db]
  (swap! db assoc :mode :editing))

(defn enter-transcript-mode! [db]
  (swap! db assoc :mode :transcript))

(defn cancel-editing! [db]
  (enter-view-mode! db))

(defn <save-page!
  ([db callback]
   (let [page-name (-> @db :current-page)
         editor (:editor @db)
         new-data ^string (.getValue editor)]
     (a/go
       (when-let [result (a/<! (e-saving/<notify-save-page page-name new-data))]
         (callback result)))))
  ([db]
   (let [callback (fn [{body-text :body}]
                    (if (nil? body-text)
                      (nav/<reload-page! db)
                      (let [body (js/JSON.parse body-text)]
                        (nav/load-page! db body))))]
     (<save-page! db callback))))

(defn <append-page!
  ([db destination body]
   (a/go
     (when-let [_ (a/<! (e-saving/<notify-append-page destination body))]
       (nav/<navigate! db destination)))))

(defn <save-card!
  [page-name hash new-val]
  (a/go
    (when-let [result (a/<! (e-saving/<notify-save-card page-name hash new-val))]
      (let [{body-text :body} result]
        (js/JSON.parse body-text)))))
