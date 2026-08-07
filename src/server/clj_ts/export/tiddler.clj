(ns clj-ts.export.tiddler
  "The per-card-type contract for the TiddlyWiki export.

  `card->tiddler-content` converts one parsed card map (from
  clj-ts.cards.parsing) into its exported form:

      {:wikitext string-or-nil   ;; contribution to the page tiddler body
       :assets   [tiddler-map]}  ;; extra tiddlers (e.g. generated SVG)

  An asset tiddler map is {:title ... :type ... :text ...}. A nil or blank
  :wikitext drops the card from the page (used for :system cards, which
  TiddlyWiki derives natively).

  Dispatch is on :source_type. The context map carries :server-snapshot,
  :page-name and :index (used to name asset tiddlers uniquely).

  Dynamic cards (:workspace, :graph) freeze to their source; see
  docs/tiddlywiki-export-design.md."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [hiccup.core :as hiccup]
            [sci.core :as sci]
            [clj-ts.cards.bookmark :as bookmark]
            [clj-ts.cards.embed :as embed]
            [clj-ts.cards.parsing :as parsing]
            [clj-ts.cards.patterning :as patterning]
            [clj-ts.cards.packaging.scheduling :as scheduling]
            [clj-ts.export.wikitext :as wikitext]
            [clj-ts.network :refer [network->svg]]
            [clj-ts.render :as render]
            [clj-ts.util :as util]))

;; region helpers

(defn- card-body
  "Card text without the leading type declaration (keyword or map)."
  [card]
  (if-let [tokens (:tokens card)]
    (or (:value (second tokens)) "")
    (:source_data card)))

(defn- frozen-source
  "The source to display for a frozen card. Cards declared as a bare
  type-declaring map (e.g. graph cards: {:card/type :graph :data [...]})
  have no body after the declaration -- the map is the content, so fall
  back to the full source."
  [card]
  (let [body (card-body card)]
    (if (str/blank? body)
      (:source_data card)
      body)))

(defn- code-fence
  ([source] (code-fence "" source))
  ([info source] (str "```" info "\n" (str/trimr source) "\n```")))

(defn- frozen-card [note info source]
  (str "//" note "//\n\n" (code-fence info source)))

(defn ensure-svg-xmlns
  "Browsers only render standalone SVG (e.g. from a data URI, which is how
  TiddlyWiki displays image tiddlers) when the root element declares the
  SVG namespace; generated SVG often omits it."
  [svg]
  (if (re-find #"<svg[^>]*\sxmlns=" svg)
    svg
    (str/replace-first svg "<svg" "<svg xmlns=\"http://www.w3.org/2000/svg\"")))

(defn- svg-asset
  "Names and wraps generated SVG as an image tiddler for this card."
  [{:keys [page-name index]} kind svg]
  {:title (str page-name "/" kind "-" index ".svg")
   :type "image/svg+xml"
   :text (ensure-svg-xmlns svg)})

(defn- media-ref
  "Reference to a media file embedded as a tiddler (titled media/<name>)."
  [file-name]
  (str "media/" file-name))

;; endregion

;; region contract

(defmulti card->tiddler-content
  (fn [_ctx card] (:source_type card)))

(defmethod card->tiddler-content :markdown [_ctx card]
  {:wikitext (wikitext/md->wikitext (card-body card))})

(defmethod card->tiddler-content :manual-copy [_ctx card]
  {:wikitext (str "@@.manual-copy\n"
                  (wikitext/md->wikitext (card-body card))
                  "\n@@")})

(defmethod card->tiddler-content :raw [_ctx card]
  {:wikitext (code-fence (card-body card))})

(defmethod card->tiddler-content :code [_ctx card]
  {:wikitext (code-fence (card-body card))})

(defmethod card->tiddler-content :evalraw [_ctx card]
  {:wikitext (code-fence (util/server-eval (card-body card)))})

(defmethod card->tiddler-content :evalmd [_ctx card]
  {:wikitext (wikitext/md->wikitext (util/server-eval (card-body card)))})

(defmethod card->tiddler-content :bookmark [_ctx card]
  {:wikitext (wikitext/md->wikitext (bookmark/bookmark-card card))})

(defmethod card->tiddler-content :patterning [ctx card]
  (let [asset (svg-asset ctx "patterning" (patterning/pattern->svg (card-body card)))]
    {:wikitext (str "[img[" (:title asset) "]]")
     :assets [asset]}))

(defmethod card->tiddler-content :network [ctx card]
  (let [svg (-> (parsing/card-map->card-data card)
                (network->svg)
                (hiccup/html))
        asset (svg-asset ctx "network" svg)]
    {:wikitext (str "[img[" (:title asset) "]]")
     :assets [asset]}))

(defmethod card->tiddler-content :filelink [_ctx card]
  (let [{:keys [file-name label]} (parsing/card-map->card-data card)]
    {:wikitext (str "[[" (or label file-name) "|" (media-ref file-name) "]]")}))

(defmethod card->tiddler-content :embed [ctx card]
  (let [data (parsing/card-map->card-data card)]
    (if (= :media-img (:type data))
      {:wikitext (str "[img[" (media-ref (:src data)) "]]")}
      ;; other embeds produce HTML, which WikiText passes through
      {:wikitext (embed/process card
                                {:user-authored? true :for-export? true}
                                render/md->html
                                (:server-snapshot ctx))})))

(defn- strip-config-map
  "Removes a leading configuration map from workspace source, mirroring
  the markdown packaging's remove-card-configuration."
  [text]
  (try
    (with-open [reader (util/string->reader text)]
      (let [first-form (edn/read reader)]
        (if (map? first-form)
          (str/trim (slurp reader))
          text)))
    (catch Exception _e
      text)))

(defn- split-public
  "Splits workspace source at the ;;;;PUBLIC separator. Code before the
  separator is private: it takes part in evaluation but is hidden from
  exported and published views."
  [source]
  (if-let [index (str/index-of source ";;;;PUBLIC")]
    {:private (subs source 0 index)
     :public (str/trim (subs source (+ index (count ";;;;PUBLIC"))))}
    {:private "" :public source}))

(defn- workspace-output
  "Best-effort static capture of a workspace's output. Workspace code
  normally runs in the browser, where client-only namespaces (stats, view,
  util, reagent, js interop) are available; pure Clojure code evaluates
  fine here, anything else yields nil. Hiccup results are rendered; string
  results pass through (workspace convention is that they contain HTML)."
  [source]
  (try
    (let [result (sci/eval-string source)]
      (cond
        (vector? result) (hiccup/html result)
        (string? result) result
        (nil? result) nil
        :else (util/html-escape (pr-str result))))
    (catch Exception _e
      nil)))

(defmethod card->tiddler-content :workspace [_ctx card]
  (let [{:keys [private public]} (-> (frozen-source card)
                                     (strip-config-map)
                                     (split-public))
        output (workspace-output (str private "\n" public))]
    {:wikitext (str (frozen-card "frozen ClojureScript workspace -- interactive in BardiganCay, shown here as source"
                                 "clojure"
                                 public)
                    (when output
                      (str "\n\n//output captured at export time://\n\n"
                           output)))}))

(defmethod card->tiddler-content :graph [_ctx card]
  {:wikitext (frozen-card "frozen graph card -- interactive in BardiganCay, shown here as source"
                          "clojure"
                          (frozen-source card))})

(defmethod card->tiddler-content :system [_ctx _card]
  ;; TiddlyWiki derives backlinks, page lists etc. natively
  {:wikitext nil})

(defmethod card->tiddler-content :default [_ctx card]
  {:wikitext (frozen-card (str "unrecognised card type " (:source_type card))
                          ""
                          (str (frozen-source card)))})

;; endregion

(defn export-card
  "Applies the contract to one card. Never throws: a failing card becomes
  an error block so it cannot sink the page export."
  [ctx card]
  (try
    (card->tiddler-content ctx card)
    (catch Exception e
      {:wikitext (frozen-card (str "error exporting " (:source_type card)
                                   " card: " (.getMessage e))
                              ""
                              (str (:source_data card)))})))

;; region cards needing the safe wrapper

(defmethod card->tiddler-content :deadline [ctx card]
  ;; the deadlines aggregation, evaluated and frozen at export time
  (let [packaged (scheduling/package-deadline 0 card {:user-authored? true}
                                              (:server-snapshot ctx))
        deadlines (edn/read-string (:server_prepared_data packaged))]
    {:wikitext (if (empty? deadlines)
                 "//no deadlines found at export time//"
                 (->> deadlines
                      (map (fn [{:keys [source-page datetime match]}]
                             (str "|[[" source-page "]]|" datetime "|"
                                  (str/trim (nth match 3 "")) "|")))
                      (str/join "\n")
                      (str "|Page|When|Deadline|h\n")))}))

(defmethod card->tiddler-content :transclude [ctx card]
  ;; snapshot semantics: the target page's cards are inlined at export time.
  ;; One level only, matching the live renderer, so cycles cannot occur.
  (if (:transcluded? ctx)
    {:wikitext "//nested transclusion is not exported//"}
    (let [{:keys [from ids]} (parsing/card-map->card-data card)
          page-store (:page-store (:server-snapshot ctx))
          matched (.get-cards-from-page page-store from ids)]
      (when (empty? matched)
        (throw (ex-info (str "no cards matching " (pr-str ids)
                             " found on page " from)
                        {:from from :ids ids})))
      (let [results (map-indexed
                     (fn [i sub-card]
                       (export-card (assoc ctx
                                           :transcluded? true
                                           :index (str (:index ctx) "-tx" i))
                                    sub-card))
                     matched)]
        {:wikitext (->> results
                        (keep :wikitext)
                        (remove str/blank?)
                        (str/join "\n\n"))
         :assets (vec (mapcat :assets results))}))))

;; endregion

(def ^:private card-separator "\n\n---\n\n")

(defn page->tiddler-content
  "Converts a page's raw source into the page tiddler body plus any asset
  tiddlers generated by its cards: {:text wikitext :assets [tiddler-map]}."
  [server-snapshot page-name raw-source]
  (let [cards (parsing/raw-text->card-maps raw-source)
        results (map-indexed
                 (fn [index card]
                   (export-card {:server-snapshot server-snapshot
                                 :page-name page-name
                                 :index index}
                                card))
                 cards)]
    {:text (->> results
                (keep :wikitext)
                (remove str/blank?)
                (str/join card-separator))
     :assets (vec (mapcat :assets results))}))
