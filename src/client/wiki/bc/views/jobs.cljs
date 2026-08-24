(ns wiki.bc.views.jobs
  (:require [reagent.core :as r]
            [wiki.bc.jobs :as jobs]
            [wiki.bc.keyboard :as keyboard]))

(defn- job-status-icon [status]
  [:span.job-status-icon {:class [:material-symbols-sharp (name status)]}
   (case status
     :accepted "schedule"
     :running "progress_activity"
     :success "check_circle"
     :failure "error"
     "help")])

(defn- active-job-item [now {:keys [label status accept-time] :as _entry}]
  [:li.jobs-view-item {:class (name status)}
   [job-status-icon status]
   [:div.jobs-view-item-body
    [:span.jobs-view-item-label label]
    [:span.jobs-view-item-meta
     (if (= status :running) "running" "queued")
     (when-let [elapsed (jobs/format-elapsed accept-time now)]
       (str " · " elapsed))]]])

(defn- download-chip [{:keys [url file-name size-bytes] :as _output}]
  [:a.jobs-view-download {:href url
                          :title (str "download " file-name)}
   [:span {:class [:material-symbols-sharp]} "download"]
   (when-let [size (jobs/format-size size-bytes)]
     [:span.jobs-view-download-size size])])

(defn- history-job-item [now {:keys [label status error-message end-time output] :as _entry}]
  [:li.jobs-view-item {:class (name status)}
   [job-status-icon status]
   [:div.jobs-view-item-body
    [:span.jobs-view-item-label label]
    (when-let [relative (jobs/format-relative-time end-time now)]
      [:span.jobs-view-item-meta relative])
    (when (= status :failure)
      [:span.jobs-view-error error-message])
    (when (and (= status :success) (seq (:failed-pages output)))
      [:span.jobs-view-warning
       {:title (str "failed pages: " (apply str (interpose ", " (:failed-pages output))))}
       (str (count (:failed-pages output)) " pages failed to export")])]
   (when (= status :success)
     [download-chip output])])

(defn- job-list-item [now entry]
  (if (jobs/active-entry? entry)
    [active-job-item now entry]
    [history-job-item now entry]))

(defn- on-key-up [db e]
  ;; note - escape doesn't fire for key-press, only key-up
  (let [key-code (.-keyCode e)]
    (cond
      (= key-code keyboard/key-escape-code)
      (jobs/exit-jobs-view! db))))

(defn jobs-view [db db-jobs]
  (let [now (r/atom (js/Date.))
        !tick-interval (atom nil)
        key-up-listener (partial on-key-up db)]

    (r/create-class
     {:component-did-mount    (fn []
                                (js/window.addEventListener "keyup" key-up-listener)
                                (reset! !tick-interval
                                        (js/setInterval (fn [] (reset! now (js/Date.))) 1000)))
      :component-will-unmount (fn []
                                (js/window.removeEventListener "keyup" key-up-listener)
                                (js/clearInterval @!tick-interval))
      :reagent-render         (fn []
                                (let [{:keys [active history]} (jobs/split-entries @db-jobs)
                                      entries (concat active history)]
                                  [:div.jobs-view
                                   (if (seq entries)
                                     [:ul.jobs-view-list
                                      (for [{:keys [job-id] :as entry} entries]
                                        ^{:key job-id} [job-list-item @now entry])]
                                     [:div.jobs-view-empty "no jobs yet"])]))})))
