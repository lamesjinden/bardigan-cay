(ns clj-ts.theme)

(defn viewing? [db-or-mode]
  (let [value @db-or-mode]
    (or (= :viewing value)
        (= :viewing (:mode value)))))

(defn- theme-value [db-or-theme]
  (cond
    (keyword? db-or-theme) db-or-theme
    (satisfies? IDeref db-or-theme) (let [v @db-or-theme]
                                      (if (map? v) (:theme v) v))))

(defn light-theme? [db-or-theme]
  (= :light (theme-value db-or-theme)))

(defn dark-theme? [db-or-theme]
  (= :dark (theme-value db-or-theme)))

(defn synthwave84-theme? [db-or-theme]
  (= :synthwave84 (theme-value db-or-theme)))

(defn set-light-theme! [db]
  (swap! db assoc :theme :light))

(defn set-dark-theme! [db]
  (swap! db assoc :theme :dark))

(defn set-synthwave84-theme! [db]
  (swap! db assoc :theme :synthwave84))

;; region DOM theming

(defn get-initial-theme [fallback-theme]
  (let [persisted-theme (js/localStorage.getItem "theme")]
    (if persisted-theme
      (keyword persisted-theme)
      fallback-theme)))

(defn- get-theme-host-element []
  (.-documentElement js/document))

(defn- get-link-element [title]
  (js/document.querySelector (str "link[title=\"" title "\"]")))

(defn- get-link-element-dark []
  (get-link-element "dark"))

(defn- get-link-element-light []
  (get-link-element "light"))

(def dark-mode-class-name "theme-dark")
(def synthwave84-mode-class-name "theme-synthwave84")

(def ^:private all-theme-class-names
  [dark-mode-class-name synthwave84-mode-class-name])

(defn- set-active-theme-class! [active-class-name]
  (let [class-list (.-classList (get-theme-host-element))]
    (doseq [class-name all-theme-class-names]
      (if (= class-name active-class-name)
        (.add class-list class-name)
        (.remove class-list class-name)))))

(defn- toggle-highlightjs-stylesheets [to-be-enabled-stylesheet to-be-disabled-stylesheet]
  (.setAttribute to-be-disabled-stylesheet "disabled" "disabled")
  (.removeAttribute to-be-enabled-stylesheet "disabled"))

(defn- apply-light-highlight! []
  (toggle-highlightjs-stylesheets (get-link-element-light) (get-link-element-dark)))

(defn- apply-dark-highlight! []
  (toggle-highlightjs-stylesheets (get-link-element-dark) (get-link-element-light)))

(defn- apply-dark-mode []
  (js/localStorage.setItem "theme" "dark")
  (set-active-theme-class! dark-mode-class-name)
  (apply-dark-highlight!))

(defn- apply-light-mode []
  (js/localStorage.setItem "theme" "light")
  (set-active-theme-class! nil)
  (apply-light-highlight!))

(defn- apply-synthwave84-mode []
  (js/localStorage.setItem "theme" "synthwave84")
  (set-active-theme-class! synthwave84-mode-class-name)
  (apply-dark-highlight!))

(defn toggle-app-theme [db]
  (let [theme (:theme @db)]
    (cond
      (= :synthwave84 theme) (apply-synthwave84-mode)
      (= :light theme) (apply-light-mode)
      :else (apply-dark-mode))))

;; endregion
