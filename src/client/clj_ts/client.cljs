(ns clj-ts.client
  (:require
   [cljs.core.async :as a]
   [reagent.core :as r]
   [reagent.dom :as dom]
   [clj-ts.confirmation.onbeforeload-process :as confirm-onbeforeload]
   [clj-ts.confirmation.edit-process :as confirm-edit]
   [clj-ts.confirmation.navigation-process :as confirm-nav]
   [clj-ts.confirmation.transcript-process :as confirm-transcript]
   [clj-ts.events.confirmation :as e-confirm]
   [clj-ts.events.editing :as e-editing]
   [clj-ts.events.navigation :as e-nav]
   [clj-ts.events.progression :as e-progress]
   [clj-ts.events.rendering :as e-rendering]
   [clj-ts.events.transcript :as e-transcript]
   [clj-ts.mode :as mode]
   [clj-ts.navigation :as nav]
   [clj-ts.rendering.render-process :as rendering-render]
   [clj-ts.theme :as theme]
   [clj-ts.views.app :refer [app]]
   [clj-ts.transcript :as transcript]))

;; region top-level ratom

(defonce db (r/atom
             {:current-page "HelloWorld"
              :raw          ""
              :transcript   (transcript/get-initial-transcript)
              :cards        []
              :wiki-name    "Wiki Name"
              :site-url     "Site URL"
              :initialized? false
              :mode         :viewing
              :theme        (theme/get-initial-theme :light)
              :env-port     4545}))

;; endregion

;; region page load

; request and load the start-page

(defn render-app []
  (let [_editing-confirmation-process (confirm-edit/<create-editor-process
                                       (e-editing/create-editing$)
                                       (e-editing/create-global-editing$))

        _nav-confirmation-process (confirm-nav/<create-nav-process
                                   (e-nav/create-navigating$)
                                   (e-editing/create-editing$))

        _onbeforeload-process (confirm-onbeforeload/<create-onbeforeload-process
                               (e-editing/create-editing$))

        _transcript-process (confirm-transcript/<create-transcript-process
                             (e-transcript/create-transcript-navigating$)
                             (e-editing/create-editing$))

        _render-process (rendering-render/<create-render-process (e-rendering/create-rendering$))

        confirmation-request$ (e-confirm/create-confirmation-request$)
        progress$ (e-progress/create-progress$)]

    (dom/render [app db confirmation-request$ progress$] (js/document.getElementById "app"))))

(defn ^:dev/after-load start []
  (render-app))

(defn ^:export init []
  (let [render$ (cond
                  (:initialized? @db)
                  (doto (a/promise-chan) (a/put! 0))

                  :else (let [init-config (first (.-init js/window))
                              init-body$ (if (object? init-config)
                                           (doto (a/promise-chan) (a/put! init-config))
                                           (nav/<get-init))]
                          (a/go
                            (let [init (a/<! init-body$)]
                              (nav/load-page! db init)
                              (mode/set-view-mode! db)
                              (swap! db assoc :initialized? true)
                              (nav/hook-pop-state db)
                              (nav/replace-state-initial)
                              (js/window.scroll 0 0)))))]
    (a/go
      (let [_ (a/<! render$)]
        (render-app)))))

;; endregion