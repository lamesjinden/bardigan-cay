(ns wiki.bc.client
  (:require
   [cljs.core.async :as a]
   [reagent.core :as r]
   [reagent.dom.client :as dom-client]
   [wiki.bc.confirmation.onbeforeload-process :as confirm-onbeforeload]
   [wiki.bc.confirmation.edit-process :as confirm-edit]
   [wiki.bc.confirmation.navigation-process :as confirm-nav]
   [wiki.bc.confirmation.transcript-process :as confirm-transcript]
   [wiki.bc.cards.move-card-process :as move-card-process]
   [wiki.bc.cards.reorder-card-process :as reorder-card-process]
   [wiki.bc.events.cards :as e-cards]
   [wiki.bc.events.confirmation :as e-confirm]
   [wiki.bc.events.editing :as e-editing]
   [wiki.bc.events.jobs :as e-jobs]
   [wiki.bc.events.navigation :as e-nav]
   [wiki.bc.events.progression :as e-progress]
   [wiki.bc.events.rendering :as e-rendering]
   [wiki.bc.events.saving :as e-saving]
   [wiki.bc.events.searching :as e-searching]
   [wiki.bc.events.transcript :as e-transcript]
   [wiki.bc.jobs.jobs-process :as jobs-process]
   [wiki.bc.mode :as mode]
   [wiki.bc.navigation :as nav]
   [wiki.bc.rendering.render-process :as rendering-render]
   [wiki.bc.saving.append-page-process :as append-page-process]
   [wiki.bc.saving.save-card-process :as save-card-process]
   [wiki.bc.saving.save-page-process :as save-page-process]
   [wiki.bc.searching.search-process :as search-process]
   [wiki.bc.theme :as theme]
   [wiki.bc.views.app :refer [app]]
   [wiki.bc.transcript :as transcript]))

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
              :env-port     4545
              :quake-mode?  false
              :jobs         {:entries []
                             :local-failures []}}))

;; endregion

;; region page load

; request and load the start-page

(defonce !app-root (delay (dom-client/create-root (js/document.getElementById "app"))))

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

        _jobs-process (jobs-process/<create-jobs-process db (e-jobs/create-jobs$))

        _save-page-process (save-page-process/<create-save-page-process (e-saving/create-saving-page$))

        _save-card-process (save-card-process/<create-save-card-process (e-saving/create-saving-card$))

        _append-page-process (append-page-process/<create-append-page-process (e-saving/create-appending-page$))

        _search-process (search-process/<create-search-process (e-searching/create-searching$))

        _move-card-process (move-card-process/<create-move-card-process (e-cards/create-moving-card$))

        _reorder-card-process (reorder-card-process/<create-reorder-card-process (e-cards/create-reordering-card$))

        confirmation-request$ (e-confirm/create-confirmation-request$)
        progress$ (e-progress/create-progress$)]

    (dom-client/render @!app-root [app db confirmation-request$ progress$])))

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