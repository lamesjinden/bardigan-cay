(ns clj-ts.routing
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [ring.util.codec :as codec]
            [ring.util.response :as resp]
            [selmer.parser]
            [selmer.util]
            [clj-ts.util :as util]
            [clj-ts.render :as render]
            [clj-ts.card-server :as card-server]
            [clj-ts.export.static-export :as export]))

(defn handle-api-system-db [{:keys [card-server] :as _request}]
  (-> @card-server
      (render/raw-db)
      (util/->html-response)))

(defn handle-api-move-card [{:keys [card-server] :as request}]
  (let [form-body (-> request :body .bytes slurp edn/read-string)
        page-name (:from form-body)
        hash (:hash form-body)
        new-page-name (:to form-body)]
    (card-server/move-card! card-server page-name hash new-page-name)
    (util/create-ok)))

(defn get-page-data [server-snapshot arguments]
  (let [source-page (card-server/resolve-source-page server-snapshot nil arguments nil)
        server-prepared-page (card-server/resolve-page server-snapshot nil arguments nil)]
    {:source_page          source-page
     :server_prepared_page server-prepared-page}))

(defn handle-api-replace-card [{:keys [card-server] :as request}]
  (let [form-body (-> request :body .bytes slurp edn/read-string)
        page-name (:page form-body)
        hash (:hash form-body)
        new-val (:data form-body)
        new-card (card-server/replace-card! card-server page-name hash new-val)]
    (if (= :not-found new-card)
      (util/create-not-found (str page-name "/" hash))
      (let [server-snapshot @card-server
            arguments {:page_name page-name}
            page-data (get-page-data server-snapshot arguments)
            response (-> (select-keys page-data [:source_page])
                         (assoc :replaced-hash hash)
                         (assoc :new-card new-card))]
        (-> response
            (json/write-str)
            (util/->json-response))))))

(defn export-page-handler [{:keys [card-server] :as request}]
  (let [page-name (-> request :params :page)
        server-snapshot @card-server
        result (export/export-one-page server-snapshot page-name)]
    (if (= result :not-found)
      (util/create-not-found page-name)
      (util/->zip-file-response result))))

(defn export-all-pages-handler [{:keys [card-server] :as _request}]
  (let [server-snapshot @card-server
        result (export/export-all-pages server-snapshot)]
    (if (= result :not-exported)
      (util/create-not-available "export all pages is not available")
      (util/->zip-file-response result))))

(def index-local-path "public/index.html")

(defn render-page-config
  ([card-server subject-file page-name]
   (let [file-content (slurp (io/resource subject-file))]
     (if page-name
       (let [server-snapshot @card-server
             page-config (get-page-data server-snapshot {:page_name page-name})
             page-config-str (json/write-str page-config)
             init-loc (render/find-init-loc file-content)
             init-content-loc (zip/down init-loc)
             init-content (zip/node init-content-loc)
             rendered-content (selmer.util/without-escaping
                               (selmer.parser/render
                                init-content
                                {:page-config page-config-str}))
             updated (zip/replace init-content-loc rendered-content)
             rendered (render/loc->html-string updated)]
         rendered)
       file-content))))

(defn handle-root-request [{:keys [card-server] :as _request}]
  (let [server-snapshot @card-server]
    (-> (render-page-config card-server index-local-path (.start-page server-snapshot))
        (util/->html-response))))

(defn handle-api-init [{:keys [card-server] :as _request}]
  (let [server-snapshot @card-server
        init-page-name (.start-page server-snapshot)
        page-config (get-page-data server-snapshot {:page_name init-page-name})
        page-config-str (json/write-str page-config)]
    (util/->json-response page-config-str)))

(defn get-page-body [card-server page-name]
  (let [arguments {:page_name page-name}
        server-snapshot @card-server]
    (json/write-str (get-page-data server-snapshot arguments))))

(defn get-page-response [card-server page-name]
  (-> (get-page-body card-server page-name)
      (util/->json-response)))

(def api-pages-request-pattern #"/api/page/(.+)")

(defn handle-api-page [{:keys [card-server] :as request}]
  (let [uri (:uri request)
        match (re-matches api-pages-request-pattern uri)
        page-name (codec/url-decode (get match 1))]
    (get-page-response card-server page-name)))

(defn handle-api-search [{:keys [card-server] :as request}]
  (let [{{query :q} :params} request
        server-snapshot @card-server]
    (-> (clj-ts.card-server/resolve-text-search server-snapshot nil {:query_string query} nil)
        (json/write-str)
        (util/->json-response))))

(def pages-request-pattern #"/pages/(.+)")

(defn handle-pages-request [{:keys [card-server] :as request}]
  (let [uri (:uri request)
        match (re-matches pages-request-pattern uri)
        page-name (codec/url-decode (get match 1))
        server-snapshot @card-server]
    (if (clj-ts.card-server/page-exists? server-snapshot page-name)
      (-> (render-page-config card-server index-local-path page-name)
          (util/->html-response))
      (-> (resp/not-found (str "Page not found " page-name))
          (resp/content-type "text")))))

(defn handle-api-save [{:keys [card-server] :as request}]
  (let [form-body (-> request :body .bytes slurp edn/read-string)
        page-name (:page form-body)
        body (:data form-body)]
    (card-server/write-page-to-file! card-server page-name body)
    (get-page-response card-server page-name)))

(defn handle-api-append [{:keys [card-server] :as request}]
  (let [form-body (-> request :body .bytes slurp edn/read-string)
        page-name (:page form-body)
        body (:data form-body)]
    (card-server/append-page! card-server page-name body)
    (get-page-response card-server page-name)))

(defn handle-api-reorder-card [{:keys [card-server] :as request}]
  (let [form-body (-> request :body .bytes slurp edn/read-string)
        page-name (:page form-body)
        hash (:hash form-body)
        direction (:direction form-body)]
    (card-server/reorder-card! card-server page-name hash direction)
    (get-page-response card-server page-name)))

(defn handle-api-rss-recent-changes [{:keys [card-server] :as _request}]
  (let [server-snapshot @card-server]
    (-> (card-server/rss-recent-changes
         server-snapshot
         (fn [page-name]
           (str (-> server-snapshot
                    :page-exporter
                    (.page-name->exported-link page-name)))))
        (resp/response)
        (resp/content-type "application/rss+xml"))))

(def media-request-pattern #"/media/(\S+)")

(defn handle-media [{:keys [card-server uri] :as _request}]
  (let [file-name (->> uri
                       (re-matches media-request-pattern)
                       second)
        server-snapshot @card-server
        file (card-server/load-media-file server-snapshot file-name)]
    (if (.isFile file)
      {:status 200
       :body   file}
      (util/create-not-found uri))))

(defn handle-not-found [{:keys [uri] :as _request}]
  (util/create-not-found uri))

(def routes {:root                   {:get handle-root-request}
             :api-init               {:get handle-api-init}
             :api-page               {:get handle-api-page}
             :pages                  {:get handle-pages-request}
             :api-system-db          {:get handle-api-system-db}
             :api-search             {:get handle-api-search}
             :api-save               {:post handle-api-save}
             :api-append             {:post handle-api-append}
             :api-move-card          {:post handle-api-move-card}
             :api-reorder-card       {:post handle-api-reorder-card}
             :api-replace-card       {:post handle-api-replace-card}
             :api-rss-recent-changes {:get handle-api-rss-recent-changes}
             :api-export-page        {:get export-page-handler}
             :api-export-all-ages    {:get export-all-pages-handler}
             :media                  {:get handle-media}})

(defn router [uri]
  (cond
    (= uri "/") :root
    (= uri "/api/init") :api-init
    (re-matches api-pages-request-pattern uri) :api-page
    (re-matches pages-request-pattern uri) :pages
    (= uri "/api/system/db") :api-system-db
    (= uri "/api/search") :api-search
    (= uri "/api/save") :api-save
    (= uri "/api/append") :api-append
    (= uri "/api/movecard") :api-move-card
    (= uri "/api/reordercard") :api-reorder-card
    (= uri "/api/replacecard") :api-replace-card
    (= uri "/api/rss/recentchanges") :api-rss-recent-changes
    (= uri "/api/exportpage") :api-export-page
    (= uri "/api/exportallpages") :api-export-all-ages
    (re-matches media-request-pattern uri) :media
    :else :not-found))

(defn request-handler [request]
  (let [uri (:uri request)
        method (:request-method request)
        handler (as-> (router uri) $
                  (get routes $ {})
                  (get $ method handle-not-found))]
    (handler request)))