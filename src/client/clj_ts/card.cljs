(ns clj-ts.card
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [cljs.core.async :as a]
            [clj-ts.page :as page]))

(defn has-link-target? [e]
  (let [tag (.-target e)
        class (.getAttribute tag "class")]
    (= class "wikilink")))

(defn wikilink-data [e]
  (when (has-link-target? e)
    (let [tag (.-target e)
          data (.getAttribute tag "data")]
      data)))

(defn- replace-card [snapshot replaced-hash new-card raw]
  (let [matching-index (->> (:cards snapshot)
                            (map-indexed (fn [i x] [i x]))
                            (filter (fn [[_i x]]
                                      (= (get x "hash") replaced-hash)))
                            (ffirst))]
    (-> snapshot
        (assoc :raw raw)
        (update-in [:cards matching-index] merge new-card))))

(defn replace-card-async! [db current-hash new-card-body]
  (let [page-name (:current-page @db)]
    (a/go
      (when-let [json (a/<! (page/<save-card! page-name current-hash new-card-body))]
        (let [edn (js->clj json)
              replaced-hash (get edn "replaced-hash")
              new-card (get edn "new-card")
              raw (get-in edn ["source_page" "body"])]
          (swap! db replace-card replaced-hash new-card raw))))))

(defn ->card-configuration
  "the card configuration is a map literal read from server_prepared_data.
   if the first form is not a map, returns nil."
  [{:strs [source_data] :or {source_data ""} :as _card}]
  (try
    (let [edn (-> source_data
                  (str/trim)
                  (edn/read-string))]
      (when (map? edn)
        edn))
    (catch :default _e
      nil)))