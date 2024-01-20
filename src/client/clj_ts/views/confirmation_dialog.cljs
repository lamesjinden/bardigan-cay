(ns clj-ts.views.confirmation-dialog
  (:require [cljs.core.async :as a]))

(defn confirmation-dialog [confirmation-request$]
  (let [!dialog (clojure.core/atom nil)
        modal-closed$ (a/chan)]

    (a/go-loop [current-request nil]
      (when-some [[value channel] (a/alts! [confirmation-request$ modal-closed$])]
        (condp = channel
          confirmation-request$ (let [dialog-element @!dialog]
                                  (.showModal dialog-element)
                                  (recur value))
          modal-closed$ (do
                          (let [out-chan (:out-chan current-request)]
                            (a/>! out-chan value))
                          (recur nil)))))

    (fn [_confirmation-request$]
      [:dialog#confirmation-dialog {:ref      (fn [element] (reset! !dialog element))
                                    :on-close (fn []
                                                (let [dialog-element @!dialog
                                                      return-value (if (= "default" (.-returnValue dialog-element))
                                                                     :ok
                                                                     :cancel)]
                                                  (set! (.-returnValue @!dialog) nil)
                                                  (a/put! modal-closed$ return-value)))}
       [:form
        [:div.dialog-description-container
         [:div "You have unsaved changes."]
         [:div "Do you want to discard them?"]]
        [:div.dialog-actions-container
         [:button.big-btn.big-btn-left {:value       "cancel"
                                        :form-method "dialog"
                                        :on-click    (fn [_] (.close @!dialog))}
          "Cancel"]
         [:button.big-btn.big-btn-right {:value    "default"
                                         :on-click (fn [e]
                                                     (.preventDefault e)
                                                     (.close @!dialog "default"))}
          "OK"]]]])))