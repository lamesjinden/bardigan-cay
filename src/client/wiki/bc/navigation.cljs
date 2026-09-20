(ns wiki.bc.navigation
  (:require [cljs.core.async :as a]
            [clojure.string :as str]
            [wiki.bc.http :as http]
            [wiki.bc.events.navigation :as nav-events]
            [wiki.bc.jobs :as jobs]
            [wiki.bc.mode :as mode]))

;; region load page

(def default-revisions
  "The per-page revision list, closed and empty."
  {:open?   false
   :entries []})

(defn load-page!
  "Loads a page response body into the app-db.

   :revision is the commit (string-keyed, as served) the loaded page is
   a read-only snapshot of, nil for the live page; :revisions are the
   page's commits, served with the page so the list opens without a
   round trip; :git-enabled? is whether the server offers revisions at
   all."
  [db body]
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
        nav-links (get server-prepared-page "nav-links")
        revision (get server-prepared-page "revision")
        revisions (vec (get server-prepared-page "revisions"))
        git-enabled? (boolean (get server-prepared-page "git_enabled"))]
    (swap! db assoc
           :current-page page-name
           :site-url site-url
           :wiki-name wiki-name
           :start-page-name start-page-name
           :raw raw
           :cards cards
           :system-cards system-cards
           :nav-links nav-links
           :revision revision
           :git-enabled? git-enabled?
           :revisions (assoc default-revisions :entries revisions)
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

(defn- <load-page! [db page-name rev]
  (a/go
    (let [completed$ (nav-events/<notify-navigating page-name rev)
          response (a/<! completed$)]
      (cond
        (= :canceled response) :canceled
        (nil? response) :failed
        :else (do
                (load-page-response db response)
                :loaded)))))

(defn <reload-page! [db]
  (<load-page! db (:current-page @db) nil))

(defn- <go-new! [db page-name rev]
  (a/go
    (let [outcome (a/<! (<load-page! db page-name rev))]
      ;; a canceled navigation must not yank the user out of an edit session
      (when (not= :canceled outcome)
        (swap! db assoc :mode :viewing))
      outcome)))

(defn- rev-query [rev]
  (when rev
    (str "?rev=" (js/encodeURIComponent rev))))

(defn page-name->url
  ([page-name]
   (page-name->url page-name nil))
  ([page-name rev]
   (if (= "/" page-name)
     "/"
     (str "/pages/" page-name (rev-query rev)))))

(defn- page-state [page-name rev]
  (cond-> {:page-name page-name}
    rev (assoc :rev rev)))

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

(defn navigate-to
  ([page-name rev]
   (push-state
    (page-state page-name rev)
    (page-name->url page-name rev)))
  ([page-name]
   (navigate-to page-name nil)))

(defn- <navigate-to! [db page-name rev]
  (a/go
    (let [outcome (a/<! (<go-new! db page-name rev))]
      ;; canceled or failed loads must not push a url the user never reached
      (when (= :loaded outcome)
        (navigate-to page-name rev))
      outcome)))

;; the live page -- links always lead to the present, even from a snapshot
(defn <navigate! [db page-name]
  (<navigate-to! db page-name nil))

;; the read-only snapshot of page-name as committed at rev
(defn <navigate-revision! [db page-name rev]
  (<navigate-to! db page-name rev))

(defn <on-link-clicked [db e target aux-clicked?]
  (.preventDefault e)
  (cond
    (or (.-ctrlKey e) aux-clicked?)
    (do
      (js/window.open (page-name->url target))
      (doto (a/promise-chan) (a/put! :open)))

    :else
    (<navigate! db target)))

;; endregion

;; region history

(defn- get-pathname [] (-> js/window .-location .-pathname))

(defn- get-rev []
  (-> (js/URLSearchParams. (-> js/window .-location .-search))
      (.get "rev")))

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

(defn- popstate->rev [popstate]
  (aget popstate "rev"))

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

      (= (get state-map "mode") "jobs")
      (jobs/enter-jobs-view! db)

      :else (let [page-name (popstate->page-name db state)
                  rev (popstate->rev state)]
              (<go-new! db page-name rev)))))

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
      (let [rev (get-rev)
            url (str (pathname->url pathname) (rev-query rev))
            page-name (pathname->page-name pathname)]
        (replace-state (page-state page-name rev) url)))))

;; endregion
