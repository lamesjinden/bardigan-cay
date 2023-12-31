(ns clj-ts.query.logic
  (:require
    [clojure.core.logic :as logic]
    [clojure.core.logic.pldb :as pldb]
    [clj-ts.storage.page_store :as pagestore]
    [clj-ts.query.facts-db :as facts]))

;; Relations in the DB
(pldb/db-rel link from to)
(pldb/db-rel page p)

(defn extract-links [server-state page-name]
  (let [text (pagestore/read-page server-state page-name)
        link-seq (re-seq #"\[\[(.+?)\]\]" text)]
    (map #(vector page-name (-> % last)) link-seq)))

(deftype FactsDb [facts]
  facts/IFactsDb

  (raw-db [_this] facts)
  (all-pages [_this]
    (sort
      (pldb/with-db facts
                    (logic/run* [p]
                                (page p)))))
  (all-links [_this]
    (sort
      (pldb/with-db facts (logic/run* [p q] (link p q)))))

  (links-to [this target]
    (let [db (.raw-db this)
          res
          (pldb/with-db db
                        (logic/run* [p q]
                                    (link p q)
                                    (logic/== target q)))]
      (sort res)))

  (broken-links [_this]
    (sort
      (pldb/with-db facts
                    (logic/run* [p q]
                                (link p q)
                                (logic/nafc page q)))))

  (orphan-pages [_this]
    (sort
      (pldb/with-db facts
                    (logic/run* [q]
                                (logic/fresh [p]
                                             (page q)
                                             (logic/conda
                                               [(link p q) logic/fail]
                                               [logic/succeed])))))))

(defn regenerate-db [server-state]
  (let [pages (.pages-as-new-directory-stream (:page-store server-state))
        pages2 (.pages-as-new-directory-stream (:page-store server-state))
        all-page-names (map pagestore/path->pagename pages)
        all-page-names2 (map pagestore/path->pagename pages2)
        extract-all-links-per-page (fn [p-names]
                                     (map (partial extract-links server-state) p-names))
        all-links (-> all-page-names2
                      extract-all-links-per-page
                      (#(apply concat %)))
        add-page (fn [db page-name]
                   (-> db (pldb/db-fact page page-name)))

        add-link (fn [db [from to]]
                   (-> db (pldb/db-fact link from to)))

        new-db (-> (pldb/db)
                   ((fn [db] (reduce add-page db all-page-names)))
                   ((fn [db] (reduce add-link db all-links))))]
    (->FactsDb new-db)))
