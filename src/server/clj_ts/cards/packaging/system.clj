(ns clj-ts.cards.packaging.system
  (:require [clj-ts.cards.system :as system]
            [clj-ts.render :as render]
            [clj-ts.search :as search]
            [clj-ts.util :as util]))

(defn- item1 [s] (str "* [[" s "]]\n"))

(defn package [id card-map render-context server-snapshot]
  (let [facts-db (-> server-snapshot :facts-db)
        page-store (-> server-snapshot :page-store)
        source-data (:source_data card-map)
        info (render/card-map->card-data card-map)
        command (:command info)]

    (condp = command
      :allpages
      (system/ldb-query->mdlist-card id source-data "All Pages" (.all-pages facts-db) :allpages item1 render-context)

      :alllinks
      (system/ldb-query->mdlist-card
       id source-data "All Links" (.all-links facts-db) :alllinks
       (fn [[a b]] (str "[[" a "]],, &#8594;,, [[" b "]]\n"))
       render-context)

      :brokenlinks
      (system/ldb-query->mdlist-card
       id source-data "Broken Internal Links" (.broken-links facts-db) :brokenlinks
       (fn [[a b]] (str "[[" a "]],, &#8603;,, [[" b "]]\n"))
       render-context)

      :orphanpages
      (system/ldb-query->mdlist-card
       id source-data "Orphan Pages" (.orphan-pages facts-db) :orphanpages item1
       render-context)

      :recentchanges
      (let [src (.read-recent-changes page-store)
            html (render/md->html src)]
        (util/package-card "recentchanges" :system :html src html render-context))

      :search
      (let [query-pattern-str (util/string->pattern-string (:query info))
            res (search/search server-snapshot query-pattern-str (:query info))
            html (render/md->html res)]
        (util/package-card "search" :system :html source-data html render-context))

      :about
      (let [sr (str "### System Information\n
**Wiki Name**,, " (:wiki-name server-snapshot) "
**PageStore Directory** (relative to code) ,, " (.page-path page-store) "
**Is Git Repo?**  ,, " (.git-repo? page-store) "
**Site Url Root** ,, " (:site-url server-snapshot) "
**Export Dir** ,, " (.export-path page-store) "
**Number of Pages** ,, " (count (.all-pages facts-db)))]
        (util/package-card id :system :markdown source-data sr render-context))

      :filelist
      (let [file-names (-> (.page-store server-snapshot) .media-list)
            file-list (str "<ul>\n"
                           (apply
                            str
                            (map #(str "<li> <a href='/media/" % "'>" % "</a></li>\n")
                                 file-names))
                           "</ul>")]
        (util/package-card id :system :html source-data file-list render-context))

      ;; not recognised
      (let [d (str "Not recognised system command in " source-data " -- cmd " command)]
        (util/package-card id :system :raw source-data d render-context)))))