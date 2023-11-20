(ns clj-ts.common
  (:require [clojure.string :as string]
            [hasch.core :refer [uuid5 edn-hash]]))

(defn split-by-hyphens [input]
  (->> (string/split input #"-{4,}")
       (map string/trim)
       (remove string/blank?)))

(defn hash-it [card-data]
  (-> card-data
      (edn-hash)
      (uuid5)))

(defn raw-card-text->card-map [raw-card-text]
  (let [regex #"^:(\S+)"
        card-text (-> raw-card-text
                      (string/trim))
        card-body (-> (string/replace-first card-text regex "")
                      (string/trim))
        card-hash (hash-it card-body)]
    (if (not (re-find regex card-text))
      {:source_type           :markdown
       :source_type_implicit? true
       :source_data           card-text
       :hash                  card-hash}
      {:source_type (->> raw-card-text (re-find regex) second keyword)
       :source_data card-body
       :hash        card-hash})))

(defn raw-text->card-maps [raw]
  (->> raw
       (split-by-hyphens)
       (map raw-card-text->card-map)))

(defn package-card [id source-type render-type source-data server-prepared-data render-context]
  {:source_type          source-type
   :render_type          render-type
   :source_data          source-data
   :server_prepared_data server-prepared-data
   :id                   id
   :hash                 (hash-it source-data)
   :user_authored?       (:user-authored? render-context)})

(defn card->raw [{:keys [source_type source_type_implicit? source_data]}]
  (if (and (= source_type :markdown) source_type_implicit?)
    (str "\n\n" source_data "\n\n")
    (str "\n" source_type "\n\n" (string/trim source_data) "\n\n")))

(defn card-is-blank? [{:keys [source_data]}]
  (= "" (string/trim source_data)))

(defn match-hash [card hash]
  (= (.toString (:hash card))
     (.toString hash)))

(defn neh
  "Not equal hash"
  [card hash]
  (not (match-hash card hash)))

;; region Cards in card list

(defn find-card-by-hash
  "Take a list of cards and return the one that matches hash or nil"
  [cards hash]
  (let [results (filter #(match-hash % hash) cards)]
    (if (> (count results) 0)
      (first results)
      nil)))

(defn remove-card-by-hash
  "Take a list of cards and return the list without the card that matches hash"
  [cards hash]
  (remove #(match-hash % hash) cards))

(defn replace-card
  "Replace the first card that matches p with new-card. If no card matches, return cards unchanged"
  [cards p new-card]
  (let [un-p #(not (p %))
        before (take-while un-p cards)
        after (rest (drop-while un-p cards))]
    (if (= 0 (count (filter p cards)))
      cards
      (concat before [new-card] after))))

(defn move-card-up
  "Move a card (id by hash) one up"
  [cards hash]
  (let [c (find-card-by-hash cards hash)]
    (if (nil? c)
      cards
      (let [before (take-while #(neh % hash) cards)
            after (rest (drop-while #(neh % hash) cards))
            res (remove nil?
                        (concat
                          (butlast before)
                          [c]
                          [(last before)]
                          after))]
        res))))

(defn move-card-down
  "Move a card (id by hash) one down"
  [cards hash]
  (let [c (find-card-by-hash cards hash)]
    (if (nil? c)
      cards
      (let [before (take-while #(neh % hash) cards)
            after (rest (drop-while #(neh % hash) cards))
            res (remove nil?
                        (concat
                          before
                          [(first after)]
                          [c]
                          (rest after)))]
        res))))

(defn cards->raw [cards]
  (->> cards
       (map card->raw)
       (string/join "----")
       (string/trim)))

;; endregion

;; region Rendering / special Markup

;; note - auto-links _probably_ never has an effect
(defn auto-links [text]
  (string/replace text #"(http(s)?\\/\\/(\S+))"
                  (str "<a href=\"$1\">$1</a>")))

(defn double-bracket-links [text]
  (string/replace
    text
    #"\[\[(.+?)\]\]"
    (str "<a class='wikilink' data='$1' href='/pages/$1'>$1</a>")))

(defn tag [t s] (str "<" t ">" s "</" t ">"))
(defn td [s] (tag "td" s))
(defn tr [s] (tag "tr" s))
(defn th [s] (tag "th" s))

(defn double-comma-table [raw]
  (loop [lines (string/split-lines raw)
         in-table false
         build []]
    (if (empty? lines)
      (if in-table
        (str (string/join "\n" build)
             "\n</table></div>")
        (string/join "\n" build))
      (let [line (first lines)]
        (if (string/includes? line ",,")
          (let [items (string/split line #",,")
                row (tr (apply str (for [i items] (td i))))]
            (if in-table
              (recur (rest lines) true (conj build row))
              (recur (rest lines) true (conj build "<div class=\"embed_div\"><table class='double-comma-table'>" row))))
          (if in-table
            (recur (rest lines) false (conj build "</table></div>" line))
            (recur (rest lines) false (conj build line))))))))

;; endregion

;; region BOILERPLATE

(defn embed-boilerplate [type]
  (condp = type
    :markdown
    "
----
"

    :youtube
    "
----
:embed

{:type :youtube
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"
}
"

    :vimeo
    "
----
:embed

{:type :vimeo
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"
}
"

    :media-img
    "
----
:embed

{:type :media-img
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"
}
"

    :img
    "
----
:embed

{:type :img
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"
}
"

    :soundcloud
    "
----
:embed

{:type :soundcloud
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"
}
"

    :bandcamp
    "
----
:embed

{:type :bandcamp
 :id IDHERE
 :url \"URL GOES HERE\"
 :description \"DESCRIPTION GOES HERE\"
 :title \"\"
 :caption \"\"
}
"

    :twitter
    "
----
:embed

{:type :twitter
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"
}
"

    :mastodon
    "
----
:embed

{:type :mastodon
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"
}
"

    :codepen
    "
----
:embed

{:type :codepen
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"
}
"

    :rss
    "
----
:embed

{:type :rss
 :url \"URL GOES HERE\"
 :caption \"\"
 :title \"\"}
"

    :oembed
    "
----
:embed

{:type :oembed
 :url \"URL GOES HERE\"
 :api \"API ENDPOINT
 :title \"\"
 :caption \"\"}
"

    (str "
----

NO BOILERPLATE FOR EMBED TYPE " type
         "
----
")))

;; endregion
