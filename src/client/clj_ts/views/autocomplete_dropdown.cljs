(ns clj-ts.views.autocomplete-dropdown
  (:require [clj-ts.keyboard :as keyboard]))

(defn autocomplete-on-key-escape [_db local-db _e]
  (swap! local-db assoc :autocomplete-visible? false))

(defn autocomplete-on-key-enter [db local-db e on-key-up-enter]
  (when-let [selected-index (:selected-index @local-db)]
    (let [{:keys [name] :as _selected-suggestion} (get-in @local-db [:suggestions selected-index])]
      (when name
        (on-key-up-enter db local-db e name)
        (swap! local-db assoc :autocomplete-visible? false)))))

(defn autocomplete-on-key-up-arrow [_db local-db e]
  (.preventDefault e)
  (when-let [selected-index (:selected-index @local-db)]
    (let [next-index (max (dec selected-index) 0)]
      (swap! local-db assoc :selected-index next-index))))

(defn autocomplete-on-key-down-arrow [_db local-db e]
  (.preventDefault e)
  (when-let [selected-index (:selected-index @local-db)]
    (let [suggestions (:suggestions @local-db)
          next-index (min (inc selected-index) (dec (count suggestions)))]
      (swap! local-db assoc :selected-index next-index))))

(defn autocomplete-on-key-up [db local-db e on-key-up-enter]
  (let [key-code (.-keyCode e)]
    (condp = key-code
      keyboard/key-escape-code (autocomplete-on-key-escape db local-db e)
      keyboard/key-enter-code (autocomplete-on-key-enter db local-db e on-key-up-enter)
      keyboard/key-down-code (autocomplete-on-key-down-arrow db local-db e)
      keyboard/key-up-code (autocomplete-on-key-up-arrow db local-db e)
      nil)))

(defn autocomplete-dropdown [db local-db on-clicked on-key-up-enter]
  (let [{:keys [suggestions autocomplete-visible?]} @local-db
        search-results (map (fn [{:keys [name]}] name) suggestions)]
    (when (and autocomplete-visible?
               (seq search-results))
      [:ul.autocomplete-dropdown {:tab-index 0
                                  :on-key-up   #(autocomplete-on-key-up db local-db % on-key-up-enter)
                                  :ref (fn [element] (swap! local-db assoc :autocomplete-element element))}
       (->> (map-indexed (fn [i result]
                           ^{:key result}
                           [:li.autocomplete-suggestion
                            {:tab-index 0
                             :data-index i
                             :class (or (when (= i (:selected-index @local-db)) "selected") "")
                             :on-click #(do
                                          (on-clicked db local-db % result)
                                          (swap! local-db assoc :autocomplete-visible? false))}
                            result])
                         search-results)
            (doall))])))