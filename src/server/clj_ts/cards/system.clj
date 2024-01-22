(ns clj-ts.cards.system
  (:require [clj-ts.render :as render]
            [clj-ts.util :as util]))

(defn ldb-query->mdlist-card [i source_data title result _qname f render-context]
  (let [items (apply str (map f result))
        body (str "*" title "* " "*(" (count result) " items)*\n\n" items)
        html (render/md->html body)]
    (util/package-card i :system :html source_data html render-context)))

(def backlinks-card-default-configuration {:display :collapsed})

(defn backlinks
  [server-snapshot page-name]
  (let [bl (.links-to server-snapshot page-name)]
    (cond
      (= bl :not-available)
      (util/package-card
        :backlinks :system :markdown
        (str backlinks-card-default-configuration "\n\n" "Backlinks Not Available")

        "Backlinks Not Available"
        false)

      (= bl '())
      (util/package-card
        :backlinks :system :markdown
        (str backlinks-card-default-configuration "\n\n" "No Backlinks")
        "No Backlinks"
        false)

      :else
      (ldb-query->mdlist-card
        "backlinks"
        (str backlinks-card-default-configuration "\n\n" "backlinks")
        "Backlinks" bl
        :calculated
        (fn [[a b]] (str "* [[" a "]] \n"))
        false))))
