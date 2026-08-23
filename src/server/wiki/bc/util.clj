(ns wiki.bc.util
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [ring.util.response :as resp]
            [sci.core :as sci])
  (:import (java.io PrintWriter PushbackReader StringWriter)
           (java.nio ByteBuffer)
           (java.security MessageDigest)
           (java.util.regex Pattern)
           (java.time LocalDate LocalDateTime ZonedDateTime ZoneId)
           (java.time.format DateTimeFormatter)))

;; Helpful for print debugging ... diffs two strings
(defn replace-whitespace [char]
  (if (Character/isWhitespace ^Character char)
    "_"
    (str char)))

(defn diff-strings [str1 str2]
  (let [len1 (count str1)
        len2 (count str2)
        min-len (min len1 len2)]
    (apply str
           (map (fn [ch1 ch2]
                  (if (= ch1 ch2)
                    (replace-whitespace ch1)
                    (str "[" (replace-whitespace ch1) (replace-whitespace ch2) "]")))
                (take min-len str1)
                (take min-len str2)))))

(defn exception-stack [e]
  (let [sw (new StringWriter)
        pw (new PrintWriter sw)]
    (.printStackTrace e pw)
    (str "Exception :: " (.getMessage e) (-> sw .toString))))

(defn create-not-found [uri-or-page-name]
  (-> (resp/not-found (str "Not found " uri-or-page-name))
      (resp/content-type "text")))

(defn create-ok []
  (-> "thank you"
      (resp/response)
      (resp/content-type "text/html")))

(defn create-not-available [body]
  (-> body
      (resp/response)
      (resp/status 503)))

(defn ->html-response [html]
  (-> html
      (resp/response)
      (resp/content-type "text/html")))

(defn ->json-response [json]
  (-> json
      (resp/response)
      (resp/content-type "application/json")))

(defn content-disposition
  "Returns an updated Ring response with the a Content-Disposition header corresponding
  to the given content-disposition."
  [resp content-disposition]
  (ring.util.response/header resp "Content-Disposition" content-disposition))

(defn server-eval
  "Evaluate Clojure code embedded in a card. Evaluated with SCI
   but on the server. I hope there's no risk for this ...
   BUT ..."
  [data]
  (let [result (sci/eval-string data)]
    (if (seqable? result)
      (apply str result)
      (str result))))

(defn nonblank [x & args]
  (if (not (str/blank? x))
    x
    (some (fn [arg] (and (not (str/blank? arg)) arg)) args)))

(defn html-escape [s]
  (str/escape s {\< "&lt;"
                 \> "&gt;"
                 \& "&amp;"
                 \' "&apos;"
                 \" "&quot;"}))

(defn- hash-escape-bytes
  "hasch.benc/encode-safe: payloads under 1024 bytes are followed by a
  same-length escape array flagging control bytes (1-29); larger payloads are
  replaced by their SHA-512 digest."
  ^bytes [^bytes u]
  (let [len (alength u)]
    (if (< len 1024)
      (let [out (byte-array (* 2 len))]
        (System/arraycopy u 0 out 0 len)
        (loop [i 0]
          (when (< i len)
            (let [e (aget u i)]
              (when (and (pos? e) (< e 30))
                (aset out (+ len i) (byte 1))))
            (recur (inc i))))
        out)
      (.digest (MessageDigest/getInstance "SHA-512") u))))

(defn hash-it
  "Deterministic content id of a card's source text.

  This is a dependency-free re-implementation of hasch 0.3.94's
  (uuid5 (edn-hash s)) for strings, and MUST keep producing identical output:
  card hashes are persisted inside user page content (transclusion :ids), so
  any change to this algorithm breaks existing wikis."
  [card-data]
  (when-not (string? card-data)
    (throw (ex-info "hash-it only hashes strings (hasch-compatible output is only implemented for them)"
                    {:type (type card-data)})))
  (let [md (MessageDigest/getInstance "SHA-512")]
    (.update md (byte-array 1 (byte 3))) ; hasch :string magic byte
    (.update md (hash-escape-bytes (.getBytes ^String card-data "UTF-8")))
    (let [bb (ByteBuffer/wrap (.digest md))
          high (.getLong bb)
          low (.getLong bb)]
      (java.util.UUID.
       (-> high
           (bit-or 0x0000000000005000)
           (bit-and 0x7fffffffffff5fff)
           (bit-clear 63)
           (bit-clear 62))
       (-> low
           (bit-set 63)
           (bit-clear 62))))))

(defn package-card [id source-type render-type source-data server-prepared-data render-context]
  {:source_type          source-type
   :render_type          render-type
   :source_data          source-data
   :server_prepared_data server-prepared-data
   :id                   id
   :hash                 (hash-it source-data)
   :user_authored?       (:user-authored? render-context)})

(defn string->pattern-string [s]
  (str "(?i)" (Pattern/quote s)))

(defn string->reader [s]
  (-> s
      (char-array)
      (io/reader)
      (PushbackReader.)))

(def iso-formatter DateTimeFormatter/ISO_DATE_TIME)

(def date-formatters
  [(DateTimeFormatter/ofPattern "yyyy/M/d")
   (DateTimeFormatter/ofPattern "M/d/yyyy")
   (DateTimeFormatter/ofPattern "yyyy-M-d")
   (DateTimeFormatter/ofPattern "M-d-yyyy")])

(defn try-parse-datetime [s]
  (if-let [parsed (try
                    (ZonedDateTime/parse s iso-formatter)
                    (catch Exception _
                      nil))]
    parsed
    (if-let [parsed (try
                      (LocalDateTime/parse s iso-formatter)
                      (catch Exception _
                        nil))]
      parsed
      nil)))

(defn try-parse-date [s]
  (try
    (some #(try
             (LocalDate/parse s %)
             (catch Exception _ nil)) date-formatters)
    (catch Exception _
      nil)))

(defn parse-datetime [s]
  (if-let [datetime (try-parse-datetime s)]
    datetime
    (if-let [date (try-parse-date s)]
      date
      nil)))

(defn datetime->iso-time [datetime]
  (when datetime
    (cond
      ;; For ZonedDateTime, convert to Instant and format using ISO_INSTANT
      (instance? java.time.ZonedDateTime datetime)
      (.format datetime DateTimeFormatter/ISO_INSTANT)

      ;; For LocalDateTime, we need a zone to convert to Instant, so use a standard format
      (instance? java.time.LocalDateTime datetime)
      (let [zoned-datetime (.atZone datetime (ZoneId/of "UTC"))]
        (.format zoned-datetime DateTimeFormatter/ISO_INSTANT))

      ;; For LocalDate, format as ISO 8601 date only
      (instance? java.time.LocalDate datetime)
      (let [zoned-datetime (-> datetime
                               (.atStartOfDay)
                               (.atZone (ZoneId/of "UTC")))]
        (.format zoned-datetime DateTimeFormatter/ISO_INSTANT))

      ;; Default case - return nil for unsupported types
      :else nil)))