(ns wiki.bc.views.revision-list
  (:require [reagent.core :as r]
            [wiki.bc.keyboard :as keyboard]
            [wiki.bc.revision :as revision]
            [wiki.bc.temporal :as temporal]))

(defn- revision-item [db page-name current-sha entry]
  (let [sha (revision/->sha entry)
        former-name (revision/former-name entry page-name)]
    [:li.revision-item
     {:class    (when (= sha current-sha) "current")
      :title    sha
      :on-click (fn [] (revision/<view-revision! db sha))}
     [:span.revision-sha (revision/->short-sha entry)]
     [:span.revision-date (temporal/format-locale (revision/->date entry))]
     [:span.revision-author (revision/->author entry)]
     [:span.revision-message (revision/->message entry)]
     [:span.revision-tags
      (when former-name
        [:span.revision-former-name {:title (str "Then named " former-name)}
         (str "(" former-name ")")])
      (when (revision/->head? entry)
        [:span.revision-tag "HEAD"])]]))

;; the list is the styled tray, so an empty history shows as its only entry
(defn- revision-entries [db page-name entries current-sha]
  [:ul.revision-list
   (if (empty? entries)
     [:li.revision-list-status "No committed revisions of this page."]
     (for [entry entries]
       ^{:key (revision/->sha entry)}
       [revision-item db page-name current-sha entry]))])

(defn- on-key-up [db e]
  ;; note - escape doesn't fire for key-press, only key-up
  (when (= (.-keyCode e) keyboard/key-escape-code)
    (revision/close-revision-list! db)))

(defn- reveal-current!
  "Scrolls the list just far enough to show the revision being viewed.
   Only the list scrolls (so not scrollIntoView, which would also drag
   the window back up to a list that is out of view)."
  [container]
  (let [list-element (some-> container (.querySelector ".revision-list"))
        item (some-> list-element (.querySelector ".revision-item.current"))]
    (when item
      (let [list-rect (.getBoundingClientRect list-element)
            item-rect (.getBoundingClientRect item)
            above (- (.-top item-rect) (.-top list-rect))
            below (- (.-bottom item-rect) (.-bottom list-rect))]
        (cond
          (neg? above)
          (set! (.-scrollTop list-element) (+ (.-scrollTop list-element) above))

          (pos? below)
          (set! (.-scrollTop list-element) (+ (.-scrollTop list-element) below)))))))

(defn revision-list [db _db-revisions _db-revision]
  (let [key-up-listener (partial on-key-up db)
        !container (clojure.core/atom nil)]
    (r/create-class
     {:component-did-mount    (fn []
                                (js/window.addEventListener "keyup" key-up-listener)
                                (reveal-current! @!container))
      ;; the viewed revision may change from outside the list (the
      ;; banner's step buttons, browser history)
      :component-did-update   (fn [] (reveal-current! @!container))
      :component-will-unmount (fn [] (js/window.removeEventListener "keyup" key-up-listener))
      :reagent-render
      (fn [db db-revisions db-revision]
        (let [{:keys [entries]} @db-revisions
              current-sha (revision/->sha @db-revision)]
          [:div.revision-list-container {:ref (fn [element] (reset! !container element))}
           [revision-entries db (:current-page @db) entries current-sha]]))})))
