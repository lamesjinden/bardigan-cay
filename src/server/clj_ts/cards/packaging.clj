(ns clj-ts.cards.packaging
  (:require [clojure.edn :as edn]
            [clj-ts.cards.parsing :as parsing]
            [clj-ts.cards.packaging.bookmark :as bookmark]
            [clj-ts.cards.packaging.code :as code]
            [clj-ts.cards.packaging.embed :as embed]
            [clj-ts.cards.packaging.evalraw :as evalraw]
            [clj-ts.cards.packaging.evalmd :as evalmd]
            [clj-ts.cards.packaging.filelink :as filelink]
            [clj-ts.cards.packaging.markdown :as markdown]
            [clj-ts.cards.packaging.manual-copy :as manual-copy]
            [clj-ts.cards.packaging.network :as network]
            [clj-ts.cards.packaging.patterning :as patterning]
            [clj-ts.cards.packaging.raw :as raw]
            [clj-ts.cards.packaging.system :as system]
            [clj-ts.cards.packaging.workspace :as workspace]
            [clj-ts.render :as render]
            [clj-ts.util :as util]))

;; Card Processing

;; We're going to use a map to store flags and other gubbins needed
;; in the rendering pipeline. Particularly to track whether we're
;; doing something in a normal rendering context or an export context
;; And whether a card is system generated or human generated.

;; We'll call it render-context
;; {:for-export false :user-authored? true}

;; todo - when to remove card-configuration?
;;        - only ever removed for 'server-prepared-data'
;;        - do not remove from:
;;          - workspace
;;          - raw

;; todo - Q: why does process-card-map return a vector of 1 element?
;;        A: b/c transclude returns a header card and the subject card, and the array unifies the result type (??)
(defn process-card-map
  [server-snapshot id {:keys [source_type source_data]} render-context]
  (try
    [(condp = source_type

       :markdown
       (markdown/package id source_data render-context)

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
       (bookmark/package id source_data render-context)

       :patterning
       (patterning/package id source_data render-context)

       :filelink
       (filelink/package id source_data render-context)

       :network
       (network/package id source_data render-context)

       :system
       (system/package id source_data render-context server-snapshot)

       :embed
       (embed/package id source_data render-context server-snapshot)

       ;; not recognised
       (util/package-card id source_type source_type source_data source_data render-context))]
    (catch
      Exception e
      [(util/package-card id :raw :raw source_data (render/process-card-error source_type source_data e) render-context)])))

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
    (concat [(util/package-card i :transclude :markdown body body render-context)] cards)))

(defn- process-card [server-snapshot i {:keys [source_type source_data] :as card-maps} render-context]
  (if (= source_type :transclude)
    (transclude server-snapshot i source_data render-context)
    (process-card-map server-snapshot i card-maps render-context)))

(defn raw->cards [server-snapshot raw render-context]
  (let [card-maps (parsing/raw-text->card-maps raw)]
    (mapcat (fn [i card-maps render-context]
              (process-card server-snapshot i card-maps render-context))
            (iterate inc 0)
            card-maps
            (repeat render-context))))