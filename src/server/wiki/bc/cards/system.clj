(ns wiki.bc.cards.system
  (:require [clojure.string :as string]
            [wiki.bc.render :as render]
            [wiki.bc.util :as util]))

(defn- mdlist-section [title result f]
  (str "*" title "* " "*(" (count result) " items)*\n\n"
       (apply str (map f result))))

(defn ldb-query->mdlist-card [i source_data title result _qname f render-context]
  (let [html (render/md->html (mdlist-section title result f))]
    (util/package-card i :system :html source_data html render-context)))

(def backlinks-card-default-configuration {:display :collapsed})

(defn backlinks
  "The backlinks system card: pages wiki-linking to page-name, plus pages
  transcluding cards from it."
  [server-snapshot page-name]
  (let [bl (.links-to server-snapshot page-name)
        transcluders (.transcluded-into server-snapshot page-name)]
    (cond
      (= bl :not-available)
      (util/package-card
       :backlinks :system :markdown
       (str backlinks-card-default-configuration "\n\n" "Backlinks Not Available")
       "Backlinks Not Available"
       false)

      (and (empty? bl) (empty? transcluders))
      (util/package-card
       :backlinks :system :markdown
       (str backlinks-card-default-configuration "\n\n" "No Backlinks")
       "No Backlinks"
       false)

      :else
      (let [sections (cond-> []
                       (seq bl)
                       (conj (mdlist-section "Backlinks" bl
                                             (fn [[a]] (str "* [[" a "]] \n"))))

                       (seq transcluders)
                       (conj (mdlist-section "Transcluded into" transcluders
                                             (fn [a] (str "* [[" a "]] \n")))))
            html (render/md->html (string/join "\n" sections))]
        (util/package-card
         "backlinks" :system :html
         (str backlinks-card-default-configuration "\n\n" "backlinks")
         html
         false)))))
