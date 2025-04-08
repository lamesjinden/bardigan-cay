(ns clj-ts.common
  (:require [clojure.string :as string]))

;; region Rendering / special Markup

(defn auto-links [text]
  (let [url-pattern #?(:clj #"((http(s)?:\/\/)([\da-z\.-]+)\.([a-z\.]{2,6})([a-zA-Z0-9\.-\/]*)*\/?)"
                       :cljs #"(http(s)?:\\/\\/(\S+))")]
    (string/replace text url-pattern (str "<a href=\"$1\">$1</a>"))))

(def double-link-pattern #"\[\[([\w\s-:]+?)\]\]")
(def double-link-pattern-ext  #"\[\[([\w\s-:]+?)\|([\w\s\-:]+?)\]\]")

(defn double-bracket-links [text]
  (-> text
      (string/replace double-link-pattern-ext "<a class='wikilink' data='$1' href='/pages/$1'>$2</a>")
      (string/replace double-link-pattern "<a class='wikilink' data='$1' href='/pages/$1'>$1</a>")))

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
{:card/type :embed
 :type :youtube
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"}
"

    :vimeo
    "
----
{:card/type :embed
 :type :vimeo
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"}
"

    :media-img
    "
----
{:card/type :embed
 :type :media-img
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"}
"

    :img
    "
----
{:card/type :embed
 :type :img
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"}
"

    :soundcloud
    "
----
{:card/type :embed
 :type :soundcloud
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"}
"

    :bandcamp
    "
----
{:card/type :embed
 :type :bandcamp
 :id IDHERE
 :url \"URL GOES HERE\"
 :description \"DESCRIPTION GOES HERE\"
 :title \"\"
 :caption \"\"}
"

    :twitter
    "
----
{:card/type :embed
 :type :twitter
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"}
"

    :mastodon
    "
----
{:card/type :embed
 :type :mastodon
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"}
"

    :codepen
    "
----
{:card/type :embed
 :type :codepen
 :url \"URL GOES HERE\"
 :title \"\"
 :caption \"\"}
"

    :rss
    "
----
{:card/type :embed
 :type :rss
 :url \"URL GOES HERE\"
 :caption \"\"
 :title \"\"}
"

    :oembed
    "
----
{:card/type :embed
 :type :oembed
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