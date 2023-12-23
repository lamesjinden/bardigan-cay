(ns clj-ts.cards.system
  (:require [clojure.edn :as edn]
            [clj-ts.cards.card-data :as card-data]
            [clj-ts.render :as render]
            [clj-ts.search :as search]))

(defn- ldb-query->mdlist-card [i source_data title result _qname f render-context]
  (let [items (apply str (map f result))
        body (str "*" title "* " "*(" (count result) " items)*\n\n" items)
        html (render/md->html body)]
    (card-data/package-card i :system :html source_data html render-context)))

(defn- item1 [s] (str "* [[" s "]]\n"))

(defn system-card
  [server-snapshot i data render-context]
  (let [info (edn/read-string data)
        command (:command info)
        facts-db (-> server-snapshot :facts-db)
        page-store (-> server-snapshot :page-store)]

    (condp = command
      :allpages
      (ldb-query->mdlist-card i data "All Pages" (.all-pages facts-db) :allpages item1 render-context)

      :alllinks
      (ldb-query->mdlist-card
        i data "All Links" (.all-links facts-db) :alllinks
        (fn [[a b]] (str "[[" a "]],, &#8594;,, [[" b "]]\n"))
        render-context)

      :brokenlinks
      (ldb-query->mdlist-card
        i data "Broken Internal Links" (.broken-links facts-db) :brokenlinks
        (fn [[a b]] (str "[[" a "]],, &#8603;,, [[" b "]]\n"))
        render-context)

      :orphanpages
      (ldb-query->mdlist-card
        i data "Orphan Pages" (.orphan-pages facts-db) :orphanpages item1
        render-context)

      :recentchanges
      (let [src (.read-recent-changes page-store)
            html (render/md->html src)]
        (card-data/package-card "recentchanges" :system :html src html render-context))

      :search
      ;; note/todo - if this path is used, then the first arg needs to be made case-insensitive (see resolve-text-search below)
      (let [res (search/search server-snapshot (:query info) (:query info))
            html (render/md->html res)]
        (card-data/package-card "search" :system :html data html render-context))

      :about
      (let [sr (str "### System Information\n
**Wiki Name**,, " (:wiki-name server-snapshot) "
**PageStore Directory** (relative to code) ,, " (.page-path page-store) "
**Is Git Repo?**  ,, " (.git-repo? page-store) "
**Site Url Root** ,, " (:site-url server-snapshot) "
**Export Dir** ,, " (.export-path page-store) "
**Number of Pages** ,, " (count (.all-pages facts-db)))]
        (card-data/package-card i :system :markdown data sr render-context))

      :filelist
      (let [file-names (-> (.page-store server-snapshot)
                           .media-list)
            file-list (str "<ul>\n"
                           (apply
                             str
                             (map #(str "<li> <a href='/media/" % "'>" % "</a></li>\n")
                                  file-names))
                           "</ul>")]
        (card-data/package-card i :system :html data file-list render-context))

      ;; not recognised
      (let [d (str "Not recognised system command in " data " -- cmd " command)]
        (card-data/package-card i :system :raw data d render-context)))))

(defn backlinks
  [server-snapshot page-name]
  (let [bl (.links-to server-snapshot page-name)]
    (cond
      (= bl :not-available)
      (card-data/package-card
        :backlinks :system :markdown
        "Backlinks Not Available"
        "Backlinks Not Available"
        false)

      (= bl '())
      (card-data/package-card
        :backlinks :system :markdown
        "No Backlinks"
        "No Backlinks"
        false)

      :else
      (ldb-query->mdlist-card
        "backlinks" "backlinks" "Backlinks" bl
        :calculated
        (fn [[a b]] (str "* [[" a "]] \n"))
        false))))
