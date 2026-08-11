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
  tiddler rather than failing the export.

  Assembly is STREAMING: the artifact is written to a file one tiddler at
  a time, and binary media is base64-encoded in bounded chunks, so peak
  heap scales with the largest single page rather than the whole corpus.
  The file body also lets http-kit stream the response from disk (http-kit
  buffers String and InputStream bodies entirely in memory, but streams
  File bodies)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-ts.export.tiddler :as tiddler])
  (:import [java.io File Writer]
           [java.nio.file Path]
           [java.text SimpleDateFormat]
           [java.util Arrays Base64 Date TimeZone]))

;; region template

(def ^:private store-marker
  "<script class=\"tiddlywiki-tiddler-store\" type=\"application/json\">")

(defn- load-template []
  (if-let [resource (io/resource "tiddlywiki/empty.html")]
    (slurp resource)
    (throw (ex-info "missing vendored resource tiddlywiki/empty.html" {}))))

(defn- split-template
  "Splits the template at the store insertion point (directly after the
  core store's closing script tag)."
  [template-html]
  (let [marker-index (str/last-index-of template-html store-marker)
        close-index (when marker-index
                      (str/index-of template-html "</script>" marker-index))]
    (when-not close-index
      (throw (ex-info "no tiddler store found in the vendored empty.html" {})))
    (let [insert-at (+ close-index (count "</script>"))]
      {:prefix (subs template-html 0 insert-at)
       :suffix (subs template-html insert-at)})))

(def ^:private template-parts
  (delay (split-template (load-template))))

;; endregion

;; region tiddler construction

(defn- tw-timestamp
  "TiddlyWiki's 17-digit UTC timestamp format, e.g. 20260807103015123."
  [^Date date]
  (let [format (doto (SimpleDateFormat. "yyyyMMddHHmmssSSS")
                 (.setTimeZone (TimeZone/getTimeZone "UTC")))]
    (.format format date)))

(defn- page-tiddlers
  "The page's own tiddler plus any asset tiddlers its cards generate.
  Reads the page unmemoized: caching every page of a large corpus in
  page-store's read-page memo would pin it all in heap."
  [server-snapshot page-name]
  (let [page-store (:page-store server-snapshot)
        source (.load-page page-store page-name)
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

;; region streaming store writer

(defn- escape-json
  "Escapes `<` in serialized JSON so tiddler content can never terminate
  the store's script element."
  [json-str]
  (str/replace json-str "<" "\\u003C"))

(defn- write-tiddler!
  "Writes one tiddler map as a JSON store entry."
  [^Writer writer first?* tiddler]
  (if @first?*
    (vreset! first?* false)
    (.write writer ","))
  (.write writer ^String (escape-json (json/write-str (update-vals tiddler str)))))

;; a multiple of 3 so every chunk base64-encodes without internal padding
(def ^:private base64-buffer-size (* 3 16384))

(defn- write-base64! [^Writer writer ^File file]
  (let [encoder (Base64/getEncoder)
        buffer (byte-array base64-buffer-size)]
    (with-open [in (io/input-stream file)]
      (loop []
        (let [n (.readNBytes in buffer 0 base64-buffer-size)]
          (when (pos? n)
            (.write writer (.encodeToString encoder
                                            (if (= n base64-buffer-size)
                                              buffer
                                              (Arrays/copyOf buffer n))))
            (recur)))))))

(defn- write-media-tiddler!
  "Writes one media file as a tiddler. Binary content streams through
  chunked base64 -- the base64 alphabet contains no JSON-special
  characters and no `<`, so it needs no escaping. SVG stays text."
  [^Writer writer first?* ^Path path]
  (let [file (.toFile path)
        file-name (str (.getFileName path))
        content-type (get media-content-types (file-extension file-name)
                          "application/octet-stream")
        modified (tw-timestamp (Date. (.lastModified file)))]
    (if (= "image/svg+xml" content-type)
      (write-tiddler! writer first?*
                      {:title (str "media/" file-name)
                       :type content-type
                       :modified modified
                       :text (tiddler/ensure-svg-xmlns (slurp file))})
      (do
        (if @first?*
          (vreset! first?* false)
          (.write writer ","))
        (.write writer ^String
                (escape-json
                 (str "{\"title\":" (json/write-str (str "media/" file-name))
                      ",\"type\":" (json/write-str content-type)
                      ",\"modified\":" (json/write-str modified)
                      ",\"text\":\"")))
        (write-base64! writer file)
        (.write writer "\"}")))))

(defn- write-store!
  "Writes the JSON tiddler store array. Returns the page failures."
  [^Writer writer server-snapshot page-names]
  (let [first?* (volatile! true)
        failures* (volatile! [])]
    (.write writer "[")
    (doseq [page-name page-names]
      (let [tiddlers (try
                       (page-tiddlers server-snapshot page-name)
                       (catch Exception e
                         (vswap! failures* conj {:page page-name
                                                 :error (.getMessage e)})
                         nil))]
        (doseq [tiddler tiddlers]
          (write-tiddler! writer first?* tiddler))))
    (let [page-store (:page-store server-snapshot)
          media-dir (-> (.as-map page-store) :page-path (.resolve "media"))]
      (when (-> media-dir .toFile .isDirectory)
        (with-open [stream (.media-files-as-new-directory-stream page-store)]
          (doseq [path stream]
            (write-media-tiddler! writer first?* path)))))
    (doseq [tiddler (site-tiddlers server-snapshot)]
      (write-tiddler! writer first?* tiddler))
    (when (seq @failures*)
      (write-tiddler! writer first?* (failures-tiddler @failures*)))
    (.write writer "]")
    @failures*))

;; endregion

(def ^:private synthetic-pages #{"AllPages" "AllLinks" "BrokenLinks" "OrphanPages"})

(defn export-wiki!
  "Streams the whole wiki into one self-contained TiddlyWiki HTML file.
  Returns {:file ^File :failures [...]}, or :not-available when the page
  database has not been generated yet. The file is a temp file marked
  delete-on-exit; callers may delete it sooner."
  [server-snapshot]
  (let [page-names (.all-pages server-snapshot)]
    (if (= :not-available page-names)
      :not-available
      (let [{:keys [prefix suffix]} @template-parts
            file (File/createTempFile "bardigancay-export-" ".html")]
        (.deleteOnExit file)
        (let [failures (with-open [writer (io/writer file :encoding "UTF-8")]
                         (.write writer ^String prefix)
                         (.write writer "\n")
                         (.write writer ^String store-marker)
                         (let [failures (write-store! writer server-snapshot
                                                      (remove synthetic-pages page-names))]
                           (.write writer "</script>")
                           (.write writer ^String suffix)
                           failures))]
          {:file file :failures failures})))))
