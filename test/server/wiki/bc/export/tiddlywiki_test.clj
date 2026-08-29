(ns wiki.bc.export.tiddlywiki-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [wiki.bc.card-server :as card-server]
            [wiki.bc.export.tiddlywiki :as tiddlywiki]
            [wiki.bc.query.facts-db :as facts]
            [wiki.bc.storage.page-store :as pagestore]
            [wiki.bc.test-fixtures :as fixtures :refer [temp-wiki-dir]])
  (:import [java.util Base64]))

(use-fixtures :each fixtures/close-tracked-indexes)

(def ^:private png-bytes
  ;; a 1x1 transparent png
  (.decode (Base64/getDecoder)
           "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="))

(defn- make-snapshot [dir]
  (let [page-store (pagestore/make-page-store (str dir))
        page-index (fixtures/tracked-index dir)]
    @(card-server/create-card-server "TestWiki" "/" 4545 "Start" [] page-index page-store)))

(def ^:private store-marker
  "<script class=\"tiddlywiki-tiddler-store\" type=\"application/json\">")

(defn- spliced-tiddlers
  "Parses the tiddlers out of the store block the export spliced in (the
  last store script in the document)."
  [html]
  (let [marker-index (str/last-index-of html store-marker)
        start (+ marker-index (count store-marker))
        end (str/index-of html "</script>" start)]
    (json/read-str (subs html start end))))

(defn- tiddler-by-title [tiddlers title]
  (first (filter #(= title (get % "title")) tiddlers)))

(defn- export
  ([snapshot] (export snapshot {}))
  ([snapshot options]
   (let [{:keys [file failures]} (tiddlywiki/export-wiki! snapshot options)]
     {:html (slurp file) :failures failures})))

(deftest include-source-option-embeds-page-markdown
  (let [dir (temp-wiki-dir {"Start" "# Hello\n\nsome **bold** text"} {})
        {:keys [html]} (export (make-snapshot dir) {:include-source? true})
        start (tiddler-by-title (spliced-tiddlers html) "Start")]
    (is (= "# Hello\n\nsome **bold** text" (get start "bc-source")))))

(deftest export-produces-a-self-contained-wiki
  (let [dir (temp-wiki-dir {"Start" "# Hello\n\nsome **bold** text"
                            "Other" "plain content"
                            "AllPages" ":system\n\n{:command :ListPages}"}
                           {"dot.png" png-bytes})
        {:keys [html failures]} (export (make-snapshot dir))
        tiddlers (spliced-tiddlers html)]
    (testing "no failures"
      (is (= [] failures)))
    (testing "the template gains exactly one extra store block"
      (is (= 2 (count (re-seq (re-pattern (java.util.regex.Pattern/quote store-marker)) html)))))
    (testing "page tiddlers carry converted WikiText, type and timestamp"
      (let [start (tiddler-by-title tiddlers "Start")]
        (is (= "! Hello\n\nsome ''bold'' text" (get start "text")))
        (is (= "text/vnd.tiddlywiki" (get start "type")))
        (is (re-matches #"\d{17}" (get start "modified")))))
    (testing "source is not embedded by default"
      (is (not (contains? (tiddler-by-title tiddlers "Start") "bc-source"))))
    (testing "synthetic pages are excluded"
      (is (nil? (tiddler-by-title tiddlers "AllPages"))))
    (testing "site tiddlers are present"
      (is (= "TestWiki" (get (tiddler-by-title tiddlers "$:/SiteTitle") "text")))
      (is (= "[[Start]]" (get (tiddler-by-title tiddlers "$:/DefaultTiddlers") "text"))))
    (testing "media files are embedded as base64 tiddlers"
      (let [media (tiddler-by-title tiddlers "media/dot.png")]
        (is (= "image/png" (get media "type")))
        (is (= (seq png-bytes)
               (seq (.decode (Base64/getDecoder) ^String (get media "text")))))))
    (testing "no ExportFailures tiddler when everything succeeds"
      (is (nil? (tiddler-by-title tiddlers "ExportFailures"))))))

(deftest script-content-cannot-break-out-of-the-store
  (let [dir (temp-wiki-dir {"Start" ":raw\n\n</script><script>alert(1)</script>"} {})
        {:keys [html]} (export (make-snapshot dir))
        tiddlers (spliced-tiddlers html)]
    ;; if the </script> in the card body were not escaped, the store block
    ;; would terminate early and this parse would fail
    (is (str/includes? (get (tiddler-by-title tiddlers "Start") "text")
                       "</script>"))))

(deftest failing-page-is-reported-not-fatal
  (let [dir (temp-wiki-dir {"Start" "fine"} {})
        snapshot (-> (make-snapshot dir)
                     ;; a page the db knows about but that has no file
                     (assoc :facts-db (reify facts/IFactsDb
                                        (all-pages [_] ["Start" "Doomed"]))))
        {:keys [html failures]} (export snapshot)
        tiddlers (spliced-tiddlers html)]
    (is (= ["Doomed"] (mapv :page failures)))
    (is (some? (tiddler-by-title tiddlers "Start")))
    (is (str/includes? (get (tiddler-by-title tiddlers "ExportFailures") "text")
                       "|Doomed|"))))
