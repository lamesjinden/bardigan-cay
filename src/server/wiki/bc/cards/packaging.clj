(ns wiki.bc.cards.packaging
  (:require
   [wiki.bc.cards.packaging.bookmark :as bookmark]
   [wiki.bc.cards.packaging.code :as code]
   [wiki.bc.cards.packaging.embed :as embed]
   [wiki.bc.cards.packaging.evalmd :as evalmd]
   [wiki.bc.cards.packaging.evalraw :as evalraw]
   [wiki.bc.cards.packaging.filelink :as filelink]
   [wiki.bc.cards.packaging.graph :as graph]
   [wiki.bc.cards.packaging.manual-copy :as manual-copy]
   [wiki.bc.cards.packaging.markdown :as markdown]
   [wiki.bc.cards.packaging.network :as network]
   [wiki.bc.cards.packaging.patterning :as patterning]
   [wiki.bc.cards.packaging.raw :as raw]
   [wiki.bc.cards.packaging.scheduling :as scheduling]
   [wiki.bc.cards.packaging.system :as system]
   [wiki.bc.cards.packaging.workspace :as workspace]
   [wiki.bc.cards.parsing :as parsing]
   [wiki.bc.render :as render]
   [wiki.bc.util :as util]))

;; Card Processing

;; We're going to use a map to store flags and other gubbins needed
;; in the rendering pipeline. Particularly to track whether we're
;; doing something in a normal rendering context or an export context
;; And whether a card is system generated or human generated.
;;
;; We'll call it render-context
;; {:for-export false :user-authored? true}

;; todo - Q: why does process-card-map return a vector of 1 element?
;;        A: b/c transclude (used to return) a header card and the subject card, and the array unifies the result type
(defn process-card-map [server-snapshot id {:keys [source_type source_data] :as card-map} render-context]
  (try
    [(condp = source_type

       :markdown
       (markdown/package id card-map render-context)

       :manual-copy
       (manual-copy/package id source_data render-context)

       :raw
       (raw/package id source_data render-context)

       :code
       (code/package id source_data render-context)

       :evalraw
       (evalraw/package id source_data render-context)

       :evalmd
       (evalmd/package id source_data render-context)

       :workspace
       (workspace/package id source_data render-context)

       :bookmark
       (bookmark/package id card-map render-context)

       :patterning
       (patterning/package id source_data render-context)

       :filelink
       (filelink/package id card-map render-context)

       :network
       (network/package id card-map render-context)

       :graph
       (graph/package id source_data render-context)

       :system
       (system/package id card-map render-context server-snapshot)

       :embed
       (embed/package id card-map render-context server-snapshot)

       :deadline
       (scheduling/package-deadline id card-map render-context server-snapshot)

       ;; not recognized
       (util/package-card id source_type source_type source_data source_data render-context))]
    (catch
     Exception e
      [(util/package-card id :raw :raw source_data (render/process-card-error source_type source_data e) render-context)])))

(defn- transclude
  [server-snapshot i card-map render-context]
  (let [data (parsing/card-map->card-data card-map)
        {:keys [from ids]} data
        page-store (.page-store server-snapshot)
        matched-cards (.get-cards-from-page page-store from ids)
        cards (->> matched-cards
                   (map-indexed (fn [index card-map]
                                  (->> (process-card-map server-snapshot (+ (* 100 i) index) card-map render-context)
                                       (map (fn [processed] (merge processed (select-keys card-map [:tx/locator])))))))
                   (mapcat identity)
                   (map (fn [processed-card]
                          (-> processed-card
                              (assoc  :transcluded {:source-page from
                                                    :hash     (:hash card-map)
                                                    :locator (:tx/locator processed-card)})
                              (dissoc :tx/locator)))))]
    cards))

(defn- process-card [server-snapshot i {:keys [source_type source_data] :as card-map} render-context]
  (if (= source_type :transclude)
    ;; a transclusion from a missing page throws from the page store;
    ;; render it as an error card (mirroring process-card-map's catch)
    ;; instead of failing the whole page
    (try
      (transclude server-snapshot i card-map render-context)
      (catch Exception e
        [(util/package-card i :raw :raw source_data (render/process-card-error source_type source_data e) render-context)]))
    (process-card-map server-snapshot i card-map render-context)))

(defn card-maps->cards [server-snapshot card-maps render-context]
  (mapcat (fn [i card-map]
            (process-card server-snapshot i card-map render-context))
          (iterate inc 0)
          card-maps))

(defn raw->cards [server-snapshot raw render-context]
  (card-maps->cards server-snapshot (parsing/raw-text->card-maps raw) render-context))