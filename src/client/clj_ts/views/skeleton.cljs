(ns clj-ts.views.skeleton)

(defn skeleton [props]
  [:div.animate-pulse.rounded-md.bg-muted props])

(defn skeleton-card []
  [:div.skeleton-root
   [skeleton {:style {:height "0.8em"
                      :width "350px"
                      :padding "8px"}}]
   [skeleton {:style {:height "0.8em"
                      :width "350px"
                      :margin-top "8px"
                      :padding "8px"}}]
   [skeleton {:style {:height "0.8em"
                      :width "250px"
                      :margin-top "8px"
                      :padding "8px"}}]])