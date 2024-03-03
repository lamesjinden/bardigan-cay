(ns clj-ts.views.paste-bar
  (:require [clj-ts.common :as common]))

(def system-search-template "----
{:card/type :system
 :command :search
 :query \"\"}

----
")

(def workspace-template "----
{:card/type :workspace}

;; Write some code
[:div
  (str \"Hello Teenage America\")]

----
")

(def evalmd-template "----
{:card/type :evalmd}

;; Write some code.
;; Note that if the result of your executed code is a number
;; You must convert it to a string.

(str \"### \" (+ 1 2 3))

----
")

(def transclude-template "----
{:card/type :transclude
 :from \"\"
 :ids [\"\"]}

----
")

(def bookmark-template "----
{:card/type :bookmark
 :url \"URL GOES HERE\"
 :title \"\"}

----
")

(defn- insert-text-at-cursor! [^js editor s]
  (when s
    (when editor
      (.insert editor s))))

(defn paste-bar
  [db]
  (let [editor (:editor @db)]
    [:div.pastebar.global-edit

             ;; blank card
     [:button.big-btn.big-btn-left
      {:on-click (fn [] (insert-text-at-cursor! editor (common/embed-boilerplate :markdown)))}
      [:span {:class [:material-symbols-sharp :clickable]} "add"]]

             ;; search
     [:button.big-btn.big-btn-middle
      {:on-click (fn [] (insert-text-at-cursor! editor system-search-template))}
      [:span {:class [:material-symbols-sharp :clickable]} "search"]]

             ;; transclude
     [:button.big-btn.big-btn-middle
      {:on-click (fn [] (insert-text-at-cursor! editor transclude-template))}
      [:span {:class [:material-symbols-sharp :clickable]} "hub"]]

             ;; bookmark
     [:button.big-btn.big-btn-middle
      {:on-click (fn [] (insert-text-at-cursor! editor bookmark-template))}
      [:span {:class [:material-symbols-sharp :clickable]} "bookmark"]]

             ;; client eval
     [:button.big-btn.big-btn-middle
      {:on-click (fn [] (insert-text-at-cursor! editor workspace-template))}
      [:svg {:xmlns "http://www.w3.org/2000/svg" :width "24" :height "24" :viewBox "0 0 512 512"} [:rect {:x "0" :y "0" :width "512" :height "512" :fill "none" :stroke "none"}] [:path {:fill "currentColor" :d "M235.825 510.657c-77.947-6.07-153.593-47.055-199.303-123.366C-61.589 223.501 49.12 15.361 235.825 1.338v25.757C69.103 41.065-29.325 227.393 58.577 374.137c40.72 67.98 107.852 104.775 177.248 110.787v25.733zM276.482 1.388v25.76c162.58 14.125 262.5 194.099 180.644 341.078c-39.82 71.5-108.93 110.518-180.644 116.704v25.732c80.53-6.242 158.394-49.706 203.079-129.942C570.933 216.652 458.547 15.606 276.482 1.388zM164.673 235.97l6.955-18.189c-41.328-17.123-82.66-2.184-82.386 50.288c.091 17.493 4.841 34.082 16.16 43.885c16.068 13.917 45.563 14.44 63.551 4.262v-19.794c-34.664 16.814-56.973 5.16-56.167-29.423c.348-14.915 4.361-29.432 15.77-34.222c14.41-6.049 36.117 3.193 36.117 3.193zm67.407-66.336h-22.47V321.03h22.469V169.634zm59.382 27.818s12.622 1.195 12.84-12.304c.23-14.299-12.84-12.84-12.84-12.84s-13.438-1.028-12.84 12.84c.599 13.868 12.84 12.304 12.84 12.304zm11.234 16.05h-23.003V333.87c-.016 14.678-8.754 20.315-27.82 14.444v18.189c26.86 7.378 51.506.005 50.823-31.564V213.502zm110.74 23.003l7.49-17.119c-34.031-15.587-78.005-8.827-77.572 21.4c-.196 36.973 57.482 30.78 56.707 51.892c-.824 22.468-46.965 9.69-56.172 4.28v19.793c4.71 3.364 70.978 20.64 78.063-20.062c7.51-43.137-56.086-37.56-56.096-56.32c-.01-20.196 41.963-6.545 47.58-3.864z"}]]]

             ;; server eval
     [:button.big-btn.big-btn-middle
      {:on-click (fn [] (insert-text-at-cursor! editor evalmd-template))}
      [:svg {:xmlns "http://www.w3.org/2000/svg" :width "24" :height "24" :viewBox "0 0 24 24"} [:rect {:x "0" :y "0" :width "24" :height "24" :fill "none" :stroke "none"}] [:path {:fill "currentColor" :d "M11.503 12.216c-.119.259-.251.549-.387.858c-.482 1.092-1.016 2.42-1.21 3.271a4.91 4.91 0 0 0-.112 1.096c0 .164.009.337.022.514c.682.25 1.417.388 2.186.39a6.39 6.39 0 0 0 2.001-.326a3.808 3.808 0 0 1-.418-.441c-.854-1.089-1.329-2.682-2.082-5.362M8.355 6.813A6.347 6.347 0 0 0 5.657 12a6.347 6.347 0 0 0 2.625 5.134c.39-1.622 1.366-3.107 2.83-6.084c-.087-.239-.186-.5-.297-.775c-.406-1.018-.991-2.198-1.513-2.733a4.272 4.272 0 0 0-.947-.729m9.172 12.464c-.84-.105-1.533-.232-2.141-.446A7.625 7.625 0 0 1 4.376 12a7.6 7.6 0 0 1 2.6-5.73a5.582 5.582 0 0 0-1.324-.162c-2.236.02-4.597 1.258-5.58 4.602c-.092.486-.07.854-.07 1.29c0 6.627 5.373 12 12 12c4.059 0 7.643-2.017 9.815-5.101c-1.174.293-2.305.433-3.271.436c-.362 0-.702-.02-1.019-.058m-2.254-2.325c.074.036.242.097.475.163a6.354 6.354 0 0 0 2.6-5.115h-.002a6.354 6.354 0 0 0-6.345-6.345a6.338 6.338 0 0 0-1.992.324c1.289 1.468 1.908 3.566 2.507 5.862l.001.003c.001.002.192.637.518 1.48c.326.842.789 1.885 1.293 2.645c.332.51.697.876.945.983M12.001 0a11.98 11.98 0 0 0-9.752 5.013c1.134-.71 2.291-.967 3.301-.957c1.394.004 2.491.436 3.017.732c.127.073.248.152.366.233A7.625 7.625 0 0 1 19.625 12a7.605 7.605 0 0 1-2.268 5.425c.344.038.709.063 1.084.061c1.328 0 2.766-.293 3.842-1.198c.703-.592 1.291-1.458 1.617-2.757c.065-.502.1-1.012.1-1.531c0-6.627-5.371-12-11.999-12"}]]]

             ;; youtube
     [:button.big-btn.big-btn-middle
      {:on-click (fn [] (insert-text-at-cursor! editor (common/embed-boilerplate :youtube)))}
      [:svg {:xmlns "http://www.w3.org/2000/svg" :width "24" :height "24" :viewBox "0 0 24 24"} [:rect {:x "0" :y "0" :width "24" :height "24" :fill "none" :stroke "none"}] [:path {:fill "currentColor" :d "m10 15l5.19-3L10 9v6m11.56-7.83c.13.47.22 1.1.28 1.9c.07.8.1 1.49.1 2.09L22 12c0 2.19-.16 3.8-.44 4.83c-.25.9-.83 1.48-1.73 1.73c-.47.13-1.33.22-2.65.28c-1.3.07-2.49.1-3.59.1L12 19c-4.19 0-6.8-.16-7.83-.44c-.9-.25-1.48-.83-1.73-1.73c-.13-.47-.22-1.1-.28-1.9c-.07-.8-.1-1.49-.1-2.09L2 12c0-2.19.16-3.8.44-4.83c.25-.9.83-1.48 1.73-1.73c.47-.13 1.33-.22 2.65-.28c1.3-.07 2.49-.1 3.59-.1L12 5c4.19 0 6.8.16 7.83.44c.9.25 1.48.83 1.73 1.73Z"}]]]

             ;; vimeo
     [:button.big-btn.big-btn-middle
      {:on-click (fn [] (insert-text-at-cursor! editor (common/embed-boilerplate :vimeo)))}
      [:svg {:xmlns "http://www.w3.org/2000/svg" :width "24" :height "24" :viewBox "0 0 24 24"} [:rect {:x "0" :y "0" :width "24" :height "24" :fill "none" :stroke "none"}] [:path {:fill "currentColor" :d "M22 7.42c-.09 1.95-1.45 4.62-4.08 8.02C15.2 19 12.9 20.75 11 20.75c-1.15 0-2.14-1.08-2.95-3.25c-.55-1.96-1.05-3.94-1.61-5.92c-.6-2.16-1.24-3.24-1.94-3.24c-.14 0-.66.32-1.56.95L2 8.07c1-.87 1.96-1.74 2.92-2.61c1.32-1.14 2.31-1.74 2.96-1.8c1.56-.16 2.52.92 2.88 3.2c.39 2.47.66 4 .81 4.6c.43 2.04.93 3.04 1.48 3.04c.42 0 1.05-.64 1.89-1.97c.84-1.32 1.29-2.33 1.35-3.03c.12-1.14-.33-1.71-1.35-1.71c-.48 0-.97.11-1.48.33c.98-3.23 2.86-4.8 5.63-4.71c2.06.06 3.03 1.4 2.91 4.01Z"}]]]

             ;; image
     [:button.big-btn.big-btn-middle
      {:on-click (fn [] (insert-text-at-cursor! editor (common/embed-boilerplate :img)))}
      [:svg {:xmlns "http://www.w3.org/2000/svg" :width "24" :height "24" :viewBox "0 0 24 24"} [:rect {:x "0" :y "0" :width "24" :height "24" :fill "none" :stroke "none"}] [:path {:fill "currentColor" :d "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"}]]]

             ;; sound cloud
     [:button.big-btn.big-btn-middle
      {:on-click (fn [] (insert-text-at-cursor! editor (common/embed-boilerplate :soundcloud)))}
      [:svg {:xmlns "http://www.w3.org/2000/svg" :width "24" :height "24" :viewBox "0 0 640 512"} [:rect {:x "0" :y "0" :width "640" :height "512" :fill "none" :stroke "none"}] [:path {:fill "currentColor" :d "m111.4 256.3l5.8 65l-5.8 68.3c-.3 2.5-2.2 4.4-4.4 4.4s-4.2-1.9-4.2-4.4l-5.6-68.3l5.6-65c0-2.2 1.9-4.2 4.2-4.2c2.2 0 4.1 2 4.4 4.2zm21.4-45.6c-2.8 0-4.7 2.2-5 5l-5 105.6l5 68.3c.3 2.8 2.2 5 5 5c2.5 0 4.7-2.2 4.7-5l5.8-68.3l-5.8-105.6c0-2.8-2.2-5-4.7-5zm25.5-24.1c-3.1 0-5.3 2.2-5.6 5.3l-4.4 130l4.4 67.8c.3 3.1 2.5 5.3 5.6 5.3c2.8 0 5.3-2.2 5.3-5.3l5.3-67.8l-5.3-130c0-3.1-2.5-5.3-5.3-5.3zM7.2 283.2c-1.4 0-2.2 1.1-2.5 2.5L0 321.3l4.7 35c.3 1.4 1.1 2.5 2.5 2.5s2.2-1.1 2.5-2.5l5.6-35l-5.6-35.6c-.3-1.4-1.1-2.5-2.5-2.5zm23.6-21.9c-1.4 0-2.5 1.1-2.5 2.5l-6.4 57.5l6.4 56.1c0 1.7 1.1 2.8 2.5 2.8s2.5-1.1 2.8-2.5l7.2-56.4l-7.2-57.5c-.3-1.4-1.4-2.5-2.8-2.5zm25.3-11.4c-1.7 0-3.1 1.4-3.3 3.3L47 321.3l5.8 65.8c.3 1.7 1.7 3.1 3.3 3.1c1.7 0 3.1-1.4 3.1-3.1l6.9-65.8l-6.9-68.1c0-1.9-1.4-3.3-3.1-3.3zm25.3-2.2c-1.9 0-3.6 1.4-3.6 3.6l-5.8 70l5.8 67.8c0 2.2 1.7 3.6 3.6 3.6s3.6-1.4 3.9-3.6l6.4-67.8l-6.4-70c-.3-2.2-2-3.6-3.9-3.6zm241.4-110.9c-1.1-.8-2.8-1.4-4.2-1.4c-2.2 0-4.2.8-5.6 1.9c-1.9 1.7-3.1 4.2-3.3 6.7v.8l-3.3 176.7l1.7 32.5l1.7 31.7c.3 4.7 4.2 8.6 8.9 8.6s8.6-3.9 8.6-8.6l3.9-64.2l-3.9-177.5c-.4-3-2-5.8-4.5-7.2zm-26.7 15.3c-1.4-.8-2.8-1.4-4.4-1.4s-3.1.6-4.4 1.4c-2.2 1.4-3.6 3.9-3.6 6.7l-.3 1.7l-2.8 160.8s0 .3 3.1 65.6v.3c0 1.7.6 3.3 1.7 4.7c1.7 1.9 3.9 3.1 6.4 3.1c2.2 0 4.2-1.1 5.6-2.5c1.7-1.4 2.5-3.3 2.5-5.6l.3-6.7l3.1-58.6l-3.3-162.8c-.3-2.8-1.7-5.3-3.9-6.7zm-111.4 22.5c-3.1 0-5.8 2.8-5.8 6.1l-4.4 140.6l4.4 67.2c.3 3.3 2.8 5.8 5.8 5.8c3.3 0 5.8-2.5 6.1-5.8l5-67.2l-5-140.6c-.2-3.3-2.7-6.1-6.1-6.1zm376.7 62.8c-10.8 0-21.1 2.2-30.6 6.1c-6.4-70.8-65.8-126.4-138.3-126.4c-17.8 0-35 3.3-50.3 9.4c-6.1 2.2-7.8 4.4-7.8 9.2v249.7c0 5 3.9 8.6 8.6 9.2h218.3c43.3 0 78.6-35 78.6-78.3c.1-43.6-35.2-78.9-78.5-78.9zm-296.7-60.3c-4.2 0-7.5 3.3-7.8 7.8l-3.3 136.7l3.3 65.6c.3 4.2 3.6 7.5 7.8 7.5c4.2 0 7.5-3.3 7.5-7.5l3.9-65.6l-3.9-136.7c-.3-4.5-3.3-7.8-7.5-7.8zm-53.6-7.8c-3.3 0-6.4 3.1-6.4 6.7l-3.9 145.3l3.9 66.9c.3 3.6 3.1 6.4 6.4 6.4c3.6 0 6.4-2.8 6.7-6.4l4.4-66.9l-4.4-145.3c-.3-3.6-3.1-6.7-6.7-6.7zm26.7 3.4c-3.9 0-6.9 3.1-6.9 6.9L227 321.3l3.9 66.4c.3 3.9 3.1 6.9 6.9 6.9s6.9-3.1 6.9-6.9l4.2-66.4l-4.2-141.7c0-3.9-3-6.9-6.9-6.9z"}]]]

             ;; bandcamp
     [:button.big-btn.big-btn-middle
      {:on-click (fn [] (insert-text-at-cursor! editor (common/embed-boilerplate :bandcamp)))}
      [:svg {:xmlns "http://www.w3.org/2000/svg" :width "24" :height "24" :viewBox "0 0 24 24"} [:rect {:x "0" :y "0" :width "24" :height "24" :fill "none" :stroke "none"}] [:path {:fill "currentColor" :d "m0 18.75l7.437-13.5H24l-7.438 13.5H0z"}]]]

             ;; twitter
     [:button.big-btn.big-btn-middle
      {:on-click (fn [] (insert-text-at-cursor! editor (common/embed-boilerplate :twitter)))}
      [:svg {:xmlns "http://www.w3.org/2000/svg" :width "24" :height "24" :viewBox "0 0 24 24"} [:rect {:x "0" :y "0" :width "24" :height "24" :fill "none" :stroke "none"}] [:path {:fill "currentColor" :d "M22.46 6c-.77.35-1.6.58-2.46.69c.88-.53 1.56-1.37 1.88-2.38c-.83.5-1.75.85-2.72 1.05C18.37 4.5 17.26 4 16 4c-2.35 0-4.27 1.92-4.27 4.29c0 .34.04.67.11.98C8.28 9.09 5.11 7.38 3 4.79c-.37.63-.58 1.37-.58 2.15c0 1.49.75 2.81 1.91 3.56c-.71 0-1.37-.2-1.95-.5v.03c0 2.08 1.48 3.82 3.44 4.21a4.22 4.22 0 0 1-1.93.07a4.28 4.28 0 0 0 4 2.98a8.521 8.521 0 0 1-5.33 1.84c-.34 0-.68-.02-1.02-.06C3.44 20.29 5.7 21 8.12 21C16 21 20.33 14.46 20.33 8.79c0-.19 0-.37-.01-.56c.84-.6 1.56-1.36 2.14-2.23Z"}]]]

             ;; rss
     [:button.big-btn.big-btn-right
      {:on-click (fn [] (insert-text-at-cursor! editor (common/embed-boilerplate :rss)))}
      [:span {:class [:material-symbols-sharp :clickable]} "rss_feed"]]]))
