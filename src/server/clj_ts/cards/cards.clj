(ns clj-ts.cards.cards
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clj-ts.cards.card-data :as card-data]
            [clj-ts.cards.embed :as embed]
            [clj-ts.cards.system :as system]
            [clj-ts.patterning :as patterning]
            [clj-ts.network :as network]
            [clj-ts.render :as render]
            [clj-ts.util :as util])
  (:import (java.io PushbackReader)))

;; Card Processing

;; We're going to use a map to store flags and other gubbins needed
;; in the rendering pipeline. Particularly to track whether we're
;; doing something in a normal rendering context or an export context
;; And whether a card is system generated or human generated.

;; We'll call it render-context
;; {:for-export false :user-authored? true}

(defn- remove-card-configuration [source_data]
  (try
    (with-open [reader (-> source_data
                           (char-array)
                           (io/reader)
                           (PushbackReader.))]
      (let [edn (edn/read reader)]
        (if (map? edn)
          (slurp reader)
          source_data)))
    (catch Exception _e
      source_data)))

(defn- bookmark-card [data]
  (let [{:keys [url timestamp title]} (edn/read-string data)
        url (util/nonblank url "url missing")
        title (util/nonblank title url)
        timestamp' (if timestamp
                     (format " \n\n_saved: %s_" timestamp)
                     "")]
    (format "\n[%s](%s)%s\n" title url timestamp')))

;; todo - Q: why does process-card-map return a vector of 1 element?
;;        A: b/c transclude returns a header card and the subject card, and the array unifies the result type (??)
(defn process-card-map
  [server-snapshot i {:keys [source_type source_data]} render-context]
  (try
    [(condp = source_type

       :markdown
       (card-data/package-card i source_type :html
                               source_data (render/md->html (remove-card-configuration source_data))
                               render-context)

       :manual-copy
       (card-data/package-card i source_type :manual-copy source_data source_data render-context)

       :raw
       (card-data/package-card i source_type :raw source_data source_data render-context)

       :code
       (card-data/package-card i :code :code source_data source_data render-context)

       :evalraw
       (card-data/package-card i :evalraw :raw source_data (util/server-eval source_data) render-context)

       :evalmd
       (card-data/package-card i :evalmd :markdown source_data (util/server-eval source_data) render-context)

       :workspace
       (card-data/package-card i source_type :workspace source_data source_data render-context)

       :system
       (system/system-card server-snapshot i source_data render-context)

       :embed
       (card-data/package-card i source_type :html
                               source_data (embed/process source_data
                                                          render-context
                                                          (if (:for-export? render-context)
                                                            (:link-renderer render-context)
                                                            (fn [s] (render/md->html s)))
                                                          server-snapshot)
                               render-context)

       :bookmark
       (card-data/package-card i :bookmark :markdown source_data (bookmark-card source_data) render-context)

       :network
       (network/network-card i source_data render-context)

       :patterning
       (card-data/package-card i :patterning :html source_data (patterning/one-pattern source_data) render-context)

       :filelink
       (card-data/package-card i :filelink :html source_data (render/file-link source_data) render-context)

       ;; not recognised
       (card-data/package-card i source_type source_type source_data source_data render-context))]
    (catch
      Exception e
      [(card-data/package-card i :raw :raw source_data (render/process-card-error source_type source_data e) render-context)])))

(defn- transclude
  [server-snapshot i source-data render-context]
  (let [{:keys [from _process ids]} (edn/read-string source-data)
        page-store (.page-store server-snapshot)
        matched-cards (.get-cards-from-page page-store from ids)
        card-maps->processed (fn [id-start card-maps render-context]
                               (mapcat (fn [i card-maps render-context]
                                         (process-card-map server-snapshot i card-maps render-context))
                                       (iterate inc id-start)
                                       card-maps
                                       (repeat render-context)))
        ;; todo - may have broken transclusion here while attempting to avoid forward declaring card-maps->processed
        cards (card-maps->processed (* 100 i) matched-cards render-context)
        body (str "### Transcluded from [[" from "]]")]
    (concat [(card-data/package-card i :transclude :markdown body body render-context)] cards)))

(defn- process-card [server-snapshot i {:keys [source_type source_data] :as card-maps} render-context]
  (if (= source_type :transclude)
    (transclude server-snapshot i source_data render-context)
    (process-card-map server-snapshot i card-maps render-context)))

(defn raw->cards [server-snapshot raw render-context]
  (let [card-maps (card-data/raw-text->card-maps raw)]
    (mapcat (fn [i card-maps render-context]
              (process-card server-snapshot i card-maps render-context))
            (iterate inc 0)
            card-maps
            (repeat render-context))))