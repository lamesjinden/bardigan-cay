(ns clj-ts.views.editor-single
  (:require [clojure.core.async :as a]
            [reagent.core :as r]
            [clj-ts.ace :as ace]
            [clj-ts.card :as cards]
            [clj-ts.keyboard :as keyboard]
            [clj-ts.events.editing :as e-editing]
            [clj-ts.theme :as theme]
            [clj-ts.views.paste-bar-single :refer [paste-bar-single]]))

(defn- single-editor-on-key-s-press [db parent-db local-db e]
  (.preventDefault e)
  (let [current-hash (:hash @parent-db)
        new-body (->> @local-db :editor (.getValue))]
    (cards/replace-card-async! db current-hash new-body)))

(defn- single-editor-on-key-down [db parent-db local-db e]
  (let [key-code (.-keyCode e)
        control? (.-ctrlKey e)]
    (when (and (= key-code keyboard/key-s-code)
               control?)
      (single-editor-on-key-s-press db parent-db local-db e))))

(defn- single-editor-on-escape-press [parent-db]
  (let [id (:hash @parent-db)]
    (a/go
      (when-let [response (a/<! (e-editing/<notify-editing-ending id))]
        (when (= response :ok)
          (swap! parent-db assoc :mode :viewing))))))

(defn- single-editor-on-key-up [parent-db e]
  ;; note - escape doesn't fire for key-press, only key-up
  (let [key-code (.-keyCode e)]
    (cond
      (= key-code keyboard/key-escape-code)
      (single-editor-on-escape-press parent-db))))

(defn- theme-tracker [db-theme local-db]
  (ace/set-theme! (:editor @local-db)
                  (if (theme/light-theme? @db-theme)
                    ace/ace-theme
                    ace/ace-theme-dark)))

(defn- destroy-editor [local-db]
  (let [editor (:editor @local-db)]
    (when editor
      (.destroy editor))))

(defn single-editor [db db-theme parent-db !editor-element]
  (let [local-db (r/atom {:editor nil
                          :editor-configured? false})
        !edit-box-container (clojure.core/atom nil)
        track-theme (r/track! (partial theme-tracker db-theme parent-db))]
    (r/create-class
     {:component-did-mount    (fn []
                                (a/go
                                  (let [theme             @db-theme
                                        source-data       (get-in @parent-db [:card "source_data"])
                                        hash              (:hash @parent-db)
                                        setup-editor-chan (ace/<setup-card-editor theme source-data hash @!editor-element @!edit-box-container)
                                        ace-instance      (a/<! setup-editor-chan)]
                                    (swap! local-db assoc :editor ace-instance :editor-configured? true))))
      :component-will-unmount (fn []
                                (destroy-editor local-db)
                                (r/dispose! track-theme)
                                (e-editing/notify-editing-end (:hash @parent-db)))
      :reagent-render         (fn []
                                [:div.editor-container {:ref   (fn [element] (reset! !edit-box-container element))
                                                        :class (when (:editor-configured? @local-db) "configured")}
                                 [paste-bar-single db parent-db local-db]
                                 [:div.edit-box-single {:ref         (fn [element] (reset! !editor-element element))
                                                        :on-key-down (fn [e] (single-editor-on-key-down db parent-db local-db e))
                                                        :on-key-up   (fn [e] (single-editor-on-key-up parent-db e))}
                                  (get-in @parent-db [:card "source_data"])]])})))
