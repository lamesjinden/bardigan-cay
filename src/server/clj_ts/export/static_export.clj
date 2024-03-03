(ns clj-ts.export.static-export
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [hiccup.core :as hiccup]
            [markdown.core :as md]
            [selmer.parser :refer [render]]
            [selmer.util :refer [without-escaping]]
            [clj-ts.card-server :as card-server]
            [clj-ts.cards.cards :as cards]
            [clj-ts.cards.packaging :as packaging]
            [clj-ts.cards.system :as system]
            [clj-ts.common :as common]
            [clj-ts.util :as util]
            [clj-ts.export.page-exporter :as page-exporter])
  (:import (java.io FileOutputStream)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time LocalDateTime)
           (java.util UUID)))

(defn double-bracket-links
  "Turn the internal double-bracket-links into real links in the exported pages"
  [text pe]
  (let [replace-link
        (fn [[_ m]]
          (if (.page-exists? (.page-store pe) m)
            (str "<a class=\"exported-internal-link\" href=\""
                 (.page-name->exported-link pe m) "\">"
                 m "</a>")
            (str "<em>" m "</em>")))]
    (str/replace text
                 #"\[\[(.+?)\]\]"
                 replace-link)))

(defn process-script [s]
  (if (str/includes? s ";;;;PUBLIC")
    (let [broken (str/split s #";;;;PUBLIC")
          private (first broken)
          public (second broken)]
      [private public])
    ["" s]))

(defn exported-workspace [card]
  (let [id (-> (hash card) (str/replace "-" ""))
        fn-name (str "run" id)
        src-name (str "id" id)
        src-name-private (str "idp" id)
        output-name (str "out" id)
        [private public] (process-script (:source_data card))
        final-script (str "(defn " fn-name " []
          (let [public-src (->
                       (.getElementById js/document \"" src-name "\")
                       .-value
                    )
               private-src (->
                       (.getElementById js/document \"" src-name-private "\")
                       .-value
                    )
               src (str private-src " "  public-src )
               result (js/scittle.core.eval_string src)
               out (-> (.getElementById js/document \"" output-name "\")
                       .-innerHTML
                       (set! result) )


]
           (.log js/console result)

            ))
")
        vname (str ".-" fn-name)
        set (str "(set! (" vname " js/window) " fn-name ")")]

    (hiccup/html
     [:div {:class "scittle-workspace"}
      [:input {:type  :hidden
               :id    src-name-private
               :value private}]
      [:textarea {:id src-name :cols 80 :rows 15}
       public]
      [:script {:type "application/x-scittle"}
       "\n"
       final-script
       "\n"
       set
       "\n"]
      [:button {:onclick (str fn-name "()")} "Run"]
      [:div {:id output-name}]])))

(defn card-specific-wrapper [card server-prepared]
  (condp = (:render_type card)
    :manual-copy
    (str "<div class='manual-copy'>" server-prepared "</div>")
    :code
    (str "<code>" server-prepared "</code>")
    :raw
    (str "<pre>" server-prepared "</pre>")
    :workspace (exported-workspace card)
    :transclude "<div class='transcluded'>" server-prepared "</div>"
    server-prepared))

(defn card->html
  "HTML for an exported card"
  [card pe]
  (let [html
        (condp = (:source_type card)
          :patterning
          (:server_prepared_data card)

          :code
          (:server_prepared_data card)

          (-> (get card :server_prepared_data)
              (common/double-comma-table)
              (md/md-to-html-string)
              (common/auto-links)
              (double-bracket-links pe)))]
    (str "<div class=\"card-outer\">
<div class=\"card\">
" (card-specific-wrapper card html)
         "</div></div>
")))

(defn ep [s]
  (if (nil? s)
    "NONE"
    s))

(defn- default-template []
  (hiccup/html
   [:html
    [:head]
    [:body
     [:div "<!-- NOTE :: Cardigan Bay couldn't find custom template, using hardwired default -->"]
     [:div {:class "navbar"}]
     [:div
      [:h1 "{{page-title}}"]]
     [:div
      "{{{page-main-content}}}"]]]))

; region PageExporter

(deftype PageExporter [page-store export-extension export-link-pattern]
  page-exporter/IPageExporter

  (as-map [this]
    {:page-store          page-store
     :export-extension    export-extension
     :export-link-pattern export-link-pattern})

  (report [this]
    (str "Export Extension :\t" (ep export-extension) "
Export Link Pattern :\t" (ep export-link-pattern) "
Example Exported Link :\t" (.page-name->exported-link this "ExamplePage")))

  (export-path [this]
    (-> this .page-store .export-path))

  (page-name->export-file-path [this page-name working-directory]
    (.resolve working-directory (str page-name export-extension)))

  (page-name->exported-link [this page-id]
    (str export-link-pattern page-id export-extension))

  (media-name->exported-link [this media-name]
    (str export-link-pattern "media/" media-name))

  (api-path [this]
    (.resolve (-> this .page-store .export-path) "api"))

  (load-template [this]
    (try
      (let [page-store (-> this .page-store)
            template-path (.resolve (.system-path page-store) "export_resources/index.html")]
        (slurp (.toString template-path)))
      (catch Exception e
        (do (println "ERROR FINDING TEMPLATE
" e "
USING DEFAULT")
            (default-template)))))

  (load-main-css [this]
    (try
      (let [page-store (-> this .page-store)
            css-path (.resolve (.system-path page-store) "export_resources/main.css")]
        (slurp (.toString css-path)))
      (catch Exception e
        (println "ERROR FINDING CSS FILE " e "
USING DEFAULT"))))

  (export-media-dir [this working-directory]
    (let [page-store (-> this .page-store)
          from-stream (.media-files-as-new-directory-stream page-store)
          destination-directory (.resolve working-directory "media")]
      (try
        (doseq [file from-stream]
          (let [destination-path (.resolve destination-directory (.getFileName file))]
            (io/make-parents (str destination-path))
            (let [new-file (new FileOutputStream (.toFile destination-path))]
              (Files/copy file new-file))))
        (catch Exception e (prn e))))))

; endregion

(defn make-page-exporter [page-store export-extension export-link-pattern]
  (->PageExporter page-store export-extension export-link-pattern))

(defn export-recentchanges-rss [server-snapshot]
  (let [api-path (-> server-snapshot :page-exporter .api-path)
        rc-rss (.resolve api-path "rc-rss.xml")
        link-fn (fn [p-name]
                  (str (:site-url server-snapshot) p-name))]
    (io/make-parents (.toString rc-rss))
    (spit (.toString rc-rss) (card-server/rss-recent-changes server-snapshot link-fn))))

(defn- export-main-css [css-str working-directory]
  (let [css-path (.resolve working-directory "main.css")]
    (try
      (io/make-parents (str css-path))
      (spit (.toString css-path) css-str)
      (catch Exception e (println "Something went wrong ... " e)))))

(defn load->cards-for-export [server-snapshot page-name link-renderer]
  (as-> server-snapshot $
    (.page-store $)
    (.load-page $ page-name)
    (packaging/raw->cards server-snapshot $ {:user-authored? true
                                             :for-export?    true
                                             :link-renderer  link-renderer})))

(defn- export-page [server-snapshot page-name template working-directory]
  (let [page-store (:page-store server-snapshot)
        exporter (:page-exporter server-snapshot)
        cards (load->cards-for-export server-snapshot page-name (fn [s] (double-bracket-links s exporter)))
        page-last-modified (.last-modified page-store page-name)
        file-name (-> (.page-name->export-file-path exporter page-name working-directory) .toString)
        rendered (str/join
                  "\n"
                  (->> cards
                       (filter #(not (cards/card-is-blank? %)))
                       (map #(card->html % exporter))))
        insert-page (hiccup/html
                     [:div
                      [:div
                       rendered]
                      [:div {:class "system"}
                       (card->html (system/backlinks server-snapshot page-name) exporter)]])
        page (without-escaping (render template
                                       {:page-title        page-name
                                        :page-main-content insert-page
                                        :time              (LocalDateTime/now)
                                        :last-modified     page-last-modified
                                        :wiki-name         (.wiki-name server-snapshot)}))]
    (io/make-parents file-name)
    (spit file-name page)))

(defn- export-pages [server-snapshot page-names job-id]
  (let [exporter (:page-exporter server-snapshot)
        export-path (.export-path exporter)
        job-id (str job-id "-" (.toString (UUID/randomUUID)))
        working-directory (-> export-path
                              (.resolve job-id)
                              (.resolve "cardigan-bay"))
        template (.load-template exporter)
        css-str (.load-main-css exporter)
        zip-file-path (-> export-path
                          (.resolve job-id)
                          (.resolve "cardigan-bay.zip"))]

    ;; ensure the working directory exists
    (Files/createDirectories working-directory (make-array FileAttribute 0))

    ;; export pages
    (let [page-results (reduce (fn [acc page-name]
                                 (try
                                   (export-page server-snapshot page-name template working-directory)
                                   (update acc :success conj page-name)
                                   (catch Exception e (do
                                                        (println "failed to export" page-name)
                                                        (println (.getMessage e))
                                                        (update acc :fail conj page-name)))))
                               {:success [] :fail []} page-names)]
      ;; add failure report to exported zip
      (when (not-empty (:fail page-results))
        (let [failed-text (str "The following pages failed to export:\n\n" (str/join "\n" (:fail page-results)))
              file-path (str (.resolve working-directory "_export-failures.txt"))]
          (spit file-path failed-text)))
      ;; css
      (export-main-css css-str working-directory)
      ;; media
      (.export-media-dir exporter working-directory)
      ;; lastly, zip
      (util/zip-directory (str working-directory) (str zip-file-path) (.getParent working-directory))
      ;; return path to resulting zip file
      zip-file-path)))

(defn export-all-pages [server-snapshot]
  (let [page-names (.all-pages server-snapshot)]
    (if (= page-names :not-available)
      :not-exported
      (let [page-names (->> (.all-pages server-snapshot)
                            (filter (fn [name]
                                      (cond
                                        (= "AllPages" name) false
                                        (= "AllLinks" name) false
                                        (= "BrokenLinks" name) false
                                        (= "OrphanPages" name) false
                                        :otherwise true))))]
        (export-pages server-snapshot page-names "all")))))

(defn export-one-page [server-snapshot page-name]
  (let [page-store (:page-store server-snapshot)]
    (if (not (.page-exists? page-store page-name))
      :not-found
      (export-pages server-snapshot [page-name] page-name))))
