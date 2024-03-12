(ns clj-ts.navigation
  (:require [cljs.core.async :as a]
            [clojure.string :as str]
            [clj-ts.http :as http]
            [clj-ts.events.navigation :as nav-events]
            [clj-ts.mode :as mode]))

;; region load page

(defn load-page! [db body]
  (let [edn (js->clj body)
        source-page (get edn "source_page")
        server-prepared-page (get edn "server_prepared_page")
        raw (get source-page "body")
        page-name (get source-page "page_name")
        cards (get server-prepared-page "cards")
        system-cards (get server-prepared-page "system_cards")
        site-url (get server-prepared-page "site_url")
        wiki-name (get server-prepared-page "wiki_name")
        start-page-name (get server-prepared-page "start_page_name")
        nav-links (get server-prepared-page "nav-links")]
    (swap! db assoc
           :current-page page-name
           :site-url site-url
           :wiki-name wiki-name
           :start-page-name start-page-name
           :raw raw
           :cards cards
           :system-cards system-cards
           :nav-links nav-links
           :mode :viewing)))

(defn load-page-response [db response]
  (let [{body-text :body} response
        body (js/JSON.parse body-text)]
    (load-page! db body)
    (js/window.scroll 0 0)))

(defn <get-init []
  (a/go
    (when-let [result (a/<! (http/<http-get "/api/init"))]
      (let [{body-text :body} result
            body (.parse js/JSON body-text)]
        body))))

(defn- <load-page! [db page-name]
  (a/go
    (let [completed$ (nav-events/<notify-navigating page-name)
          response (a/<! completed$)]
      (when (and (not (nil? response))
                 (not (= :canceled response)))
        (load-page-response db response)))))

(defn <reload-page! [db]
  (<load-page! db (:current-page @db)))

(defn- <go-new! [db page-name]
  (a/go
    (a/<! (<load-page! db page-name))
    (swap! db assoc :mode :viewing)))

(defn page-name->url [page-name]
  (if (= "/" page-name)
    "/"
    (str "/pages/" page-name)))

(defn push-state
  ([state-map url]
   (let [state-map-js (clj->js state-map)]
     (if-let [history-state-js (.-state js/history)]
       (when-not (= (js->clj history-state-js)
                    (js->clj state-map-js))
         (js/history.pushState state-map-js "" url))
       (js/history.pushState state-map-js "" url))))
  ([state-map]
   (push-state state-map "")))

(defn navigate-to [page-name]
  (let [url (page-name->url page-name)
        state {:page-name page-name}]
    (push-state state url)))

(defn <navigate! [db page-name]
  (a/go
    (a/<! (<go-new! db page-name))
    (navigate-to page-name)))

(defn <on-link-clicked [db e target aux-clicked?]
  (.preventDefault e)
  (cond
    (or (.-ctrlKey e) aux-clicked?)
    (let [chan (a/put! (a/promise-chan) :open)]
      (js/window.open (page-name->url target))
      chan)

    :else
    (<navigate! db target)))

;; endregion

;; region history

(defn- get-pathname [] (-> js/window .-location .-pathname))

(defn- pathname->url
  ([pathname]
   (let [url (if (= "/" pathname)
               "/"
               (let [split (str/split pathname #"/")]
                 (str "/pages/" (last split))))]
     url))
  ([] (pathname->url (get-pathname))))

(defn- pathname->page-name
  ([pathname]
   (let [page-name (if (= "/" pathname)
                     "/"
                     (let [split (str/split pathname #"/")]
                       (last split)))]
     page-name))
  ([] (pathname->page-name (get-pathname))))

(defn- popstate->page-name [db popstate]
  (let [page-name (aget popstate "page-name")
        page-name (if (or (= "/" page-name) (= "index.html" page-name))
                    (:start-page-name @db)
                    page-name)]
    page-name))

(defn- replace-state
  ([state-map url]
   (js/history.replaceState (clj->js state-map) "" url))
  ([state-map]
   (replace-state state-map "")))

;; note - push-state resides above

(defn- pop-state-handler [db state]
  (let [state-map (js->clj state true)]
    (cond
      (= (get state-map "mode") "transcript")
      (mode/set-transcript-mode! db)

      :else (let [page-name (popstate->page-name db state)]
              (<go-new! db page-name)))))

;; endregion

;; region public history api

(defn hook-pop-state [db]
  (js/window.addEventListener "popstate" (fn [e]
                                           (let [state (.-state e)]
                                             (pop-state-handler db state)))))

(defn replace-state-initial []
  (let [pathname (get-pathname)]
    (if (= "/index.html" pathname)
      (replace-state {:page-name "index.html"} pathname)
      (let [url (pathname->url pathname)
            page-name (pathname->page-name pathname)
            state {:page-name page-name}]
        (replace-state state url)))))

;; endregion
