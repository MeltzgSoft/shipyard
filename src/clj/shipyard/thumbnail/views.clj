(ns shipyard.thumbnail.views)

(defn progress
  ([] (progress nil))
  ([counts]
   [:p.thumbnail-progress
    {:role "status" :data-thumbnail-progress true :aria-live "polite"
     :hx-get "/thumbnail-progress" :hx-trigger (if counts "every 600ms" "load") :hx-target "this" :hx-swap "outerHTML"
     :hx-sync "this:drop"}
    (if counts
      (str "Thumbnail generation: " (:running counts) " running · " (:queued counts) " queued")
      "Checking thumbnail generation…")]))

(defn preview [{:keys [state key]} url target label]
  (case state
    :ready [:img {:src (str "/thumbnail-images/" key) :width 128 :height 88 :alt (str "Preview of " label)}]
    :preparing [:span {:hx-get url :hx-trigger "load delay:600ms" :hx-target target} "…"]
    [:span "Preview unavailable"]))
