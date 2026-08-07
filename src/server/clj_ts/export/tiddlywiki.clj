(ns clj-ts.export.tiddlywiki
  "Assembles the complete TiddlyWiki export.

  The deliverable is one self-contained HTML file: the vendored
  resources/tiddlywiki/empty.html (see the VERSION note beside it) with an
  additional JSON tiddler store spliced in after the core store. The
  TiddlyWiki boot code reads every `script.tiddlywiki-tiddler-store` block
  in document order, so tiddlers in the spliced store override same-titled
  core tiddlers.

  Each page becomes one tiddler (body via the card contract in
  clj-ts.export.tiddler) carrying the original page markdown in a
  `bc-source` field. Media files are embedded as base64 tiddlers titled
  media/<name>. Page-level failures are collected into an ExportFailures
  tiddler rather than failing the export."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-ts.export.tiddler :as tiddler]
            [clj-ts.storage.page-store :as pagestore])
  (:import [java.text SimpleDateFormat]
           [java.util Base64 Date TimeZone]
           [java.nio.file Files Path]))

;; region tiddler construction

(defn- tw-timestamp
  "TiddlyWiki's 17-digit UTC timestamp format, e.g. 20260807103015123."
  [^Date date]
  (let [format (doto (SimpleDateFormat. "yyyyMMddHHmmssSSS")
                 (.setTimeZone (TimeZone/getTimeZone "UTC")))]
    (.format format date)))

(defn- page-tiddlers
  "The page's own tiddler plus any asset tiddlers its cards generate."
  [server-snapshot page-name]
  (let [page-store (:page-store server-snapshot)
        source (pagestore/read-page server-snapshot page-name)
        {:keys [text assets]} (tiddler/page->tiddler-content server-snapshot page-name source)]
    (into [{:title page-name
            :text text
            :type "text/vnd.tiddlywiki"
            :modified (tw-timestamp (.last-modified page-store page-name))
            :bc-source source}]
          assets)))

(def ^:private media-content-types
  {"png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "gif"  "image/gif"
   "svg"  "image/svg+xml"
   "webp" "image/webp"
   "ico"  "image/x-icon"
   "pdf"  "application/pdf"
   "mp3"  "audio/mpeg"
   "mp4"  "video/mp4"
   "webm" "video/webm"})

(defn- file-extension [file-name]
  (some-> (re-find #"\.([^.]+)\z" file-name) second str/lower-case))

(defn- media-file->tiddler [^Path path]
  (let [file (.toFile path)
        file-name (str (.getFileName path))
        content-type (get media-content-types (file-extension file-name)
                          "application/octet-stream")
        ;; svg is a text type in TiddlyWiki; everything else binary/base64
        text (if (= "image/svg+xml" content-type)
               (tiddler/ensure-svg-xmlns (slurp file))
               (.encodeToString (Base64/getEncoder) (Files/readAllBytes path)))]
    {:title (str "media/" file-name)
     :type content-type
     :text text
     :modified (tw-timestamp (Date. (.lastModified file)))}))

(defn- media-tiddlers [page-store]
  (let [media-dir (-> (.as-map page-store) :page-path (.resolve "media"))]
    (if (-> media-dir .toFile .isDirectory)
      (with-open [stream (.media-files-as-new-directory-stream page-store)]
        (mapv media-file->tiddler stream))
      [])))

(defn- site-tiddlers [server-snapshot]
  [{:title "$:/SiteTitle"
    :text (:wiki-name server-snapshot)}
   {:title "$:/SiteSubtitle"
    :text "a BardiganCay export"}
   {:title "$:/DefaultTiddlers"
    :text (str "[[" (:start-page server-snapshot) "]]")}])

(defn- failures-tiddler [failures]
  {:title "ExportFailures"
   :type "text/vnd.tiddlywiki"
   :text (str "The following pages failed to export:\n\n"
              "|Page|Error|h\n"
              (->> failures
                   (map (fn [{:keys [page error]}]
                          (str "|" page "|" error "|")))
                   (str/join "\n")))})

;; endregion

;; region store assembly

(defn- tiddlers->store-json
  "Serializes tiddlers to the TiddlyWiki JSON store format. All field values
  are strings; `<` is escaped so tiddler content can never terminate the
  store's script element."
  [tiddlers]
  (-> (json/write-str (map #(update-vals % str) tiddlers))
      (str/replace "<" "\\u003C")))

(def ^:private store-marker
  "<script class=\"tiddlywiki-tiddler-store\" type=\"application/json\">")

(defn- splice-store
  "Inserts a tiddler store into the template directly after the core store."
  [template-html store-json]
  (let [marker-index (str/last-index-of template-html store-marker)
        close-index (when marker-index
                      (str/index-of template-html "</script>" marker-index))]
    (when-not close-index
      (throw (ex-info "no tiddler store found in the vendored empty.html" {})))
    (let [insert-at (+ close-index (count "</script>"))]
      (str (subs template-html 0 insert-at)
           "\n" store-marker store-json "</script>"
           (subs template-html insert-at)))))

(defn- load-template []
  (if-let [resource (io/resource "tiddlywiki/empty.html")]
    (slurp resource)
    (throw (ex-info "missing vendored resource tiddlywiki/empty.html" {}))))

;; endregion

(def ^:private synthetic-pages #{"AllPages" "AllLinks" "BrokenLinks" "OrphanPages"})

(defn export-wiki
  "Exports the whole wiki as one self-contained TiddlyWiki HTML string.
  Returns {:html ... :failures [...]}, or :not-available when the page
  database has not been generated yet."
  [server-snapshot]
  (let [page-names (.all-pages server-snapshot)]
    (if (= :not-available page-names)
      :not-available
      (let [results (->> page-names
                         (remove synthetic-pages)
                         (map (fn [page-name]
                                (try
                                  {:tiddlers (page-tiddlers server-snapshot page-name)}
                                  (catch Exception e
                                    {:failure {:page page-name
                                               :error (.getMessage e)}})))))
            failures (vec (keep :failure results))
            tiddlers (concat (mapcat :tiddlers results)
                             (media-tiddlers (:page-store server-snapshot))
                             (site-tiddlers server-snapshot)
                             (when (seq failures)
                               [(failures-tiddler failures)]))]
        {:html (splice-store (load-template) (tiddlers->store-json tiddlers))
         :failures failures}))))
