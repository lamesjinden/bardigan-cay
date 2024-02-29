(ns clj-ts.card
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [cljs.core.async :as a]
            [cljs.tools.reader.reader-types :as reader-types]
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

(defn ->card-configuration
  "attmpts to read the card-configuration map from the 'source_data' key of `card`.
   
   * if the first form is a keyword, attemps to read the next form. 
     when the second form is a map, it is returned as the card-configuration.
   
   * if the first form is a map, it is returned as the card configuration.
   
   * otherwise, returns nil."
  [card]
  (try
    (let [source_data (-> card (get "source_data" "") (str/trim))
          reader (reader-types/string-push-back-reader source_data)
          a (edn/read reader)]
      (cond
        (keyword? a)
        (let [b (edn/read reader)]
          (if (map? b)
            b
            nil))

        (map? a)
        a

        :else nil))
    (catch :default _e
      nil)))

(defn- replace-card [snapshot source-page replaced-hash new-card raw]
  (let [transcluded? (not (= source-page (:current-page snapshot)))]
    (if-let [matching-index (if transcluded?
                              (->> (:cards snapshot)
                                   (map-indexed (fn [i x] (assoc x :display-index i)))
                                   (filter (fn [card]
                                             (= source-page (get-in card ["transcluded" "source-page"]))))
                                   (filter (fn [card]
                                             (= replaced-hash (:card/id (->card-configuration card)))))
                                   (map (fn [card] (:display-index card)))
                                   (first))
                              (->> (:cards snapshot)
                                   (map-indexed (fn [i x] (assoc x :display-index i)))
                                   (filter (fn [card]
                                             (= (get card "hash") replaced-hash)))
                                   (map (fn [card] (:display-index card)))
                                   (first)))]
      (-> snapshot
          (assoc :raw raw)
          (update-in [:cards matching-index] merge new-card))
      (do
        (js/console.debug (str "no matching index: " "transcluded?=" transcluded? "; replaced-hash=" replaced-hash))
        snapshot))))

(defn replace-card-async!
  ([db page-name current-hash new-card-body]
   (a/go
     (when-let [json (a/<! (page/<save-card! page-name current-hash new-card-body))]
       (let [edn (js->clj json)
             replaced-hash (get edn "replaced-hash")
             new-card (get edn "new-card")
             source-page (get-in edn ["source_page" "page_name"])
             raw (get-in edn ["source_page" "body"])]
         (swap! db replace-card source-page replaced-hash new-card raw)))))
  ([db current-hash new-card-body]
   (let [page-name (:current-page @db)]
     (replace-card-async! db page-name current-hash new-card-body))))
