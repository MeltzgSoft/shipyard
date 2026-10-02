(ns shipyard.thumbnail.views)

(defn preview [{:keys [state key]} url target label]
  (case state
    :ready [:img {:src (str "/thumbnail-images/" key) :width 128 :height 88 :alt (str "Preview of " label)}]
    :preparing [:span {:hx-get url :hx-trigger "load delay:600ms" :hx-target target} "…"]
    [:span "Preview unavailable"]))
