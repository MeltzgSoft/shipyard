(ns shipyard.thumbnail.views
  (:require [clojure.string :as str]))

(defn lazy-attrs [url root disable?]
  {:hx-get url :hx-trigger (str "intersect once root:" root)
   :hx-sync "this:drop" :hx-target "this" :hx-swap "innerHTML"
   :hx-disabled-elt (if disable? "this" "unset")})

(defn progress
  ([] (progress nil))
  ([counts]
   [:p.thumbnail-progress
    {:role "status" :data-thumbnail-progress true :aria-live "polite"
     :hx-get "/thumbnail-progress" :hx-trigger (if counts "every 600ms" "load") :hx-target "this" :hx-swap "outerHTML"
     :hx-sync "this:drop" :hx-disabled-elt "unset"}
    (if counts
      (str "Thumbnail generation: " (:running counts) " running · " (:queued counts) " queued")
      "Checking thumbnail generation…")]))

(defn preview [{:keys [state key]} url target label]
  (case state
    :ready [:img {:src (str "/thumbnail-images/" key) :width 128 :height 88 :alt (str "Preview of " label)}]
    :preparing [:span {:hx-get url :hx-trigger "load delay:600ms" :hx-target target} "…"]
    :overloaded [:span "Preview queue full. " [:button {:type "button" :hx-get url :hx-target target} "Retry preview"]]
    :failed [:span "Preview unavailable. " [:button {:type "button" :hx-get (str url (if (str/includes? url "?") "&" "?") "retry=1") :hx-target target} "Retry preview"]]
    [:span "Preview unavailable"]))

(defn import-progress [counts]
  [:p.import-progress {:role "status" :data-import-progress true :aria-live "polite"
                       :hx-get "/imports/progress" :hx-trigger (if counts "every 600ms" "load")
                       :hx-target "this" :hx-swap "outerHTML" :hx-sync "this:drop" :hx-disabled-elt "unset"
                       :data-accepted (:accepted counts) :data-pending (:pending counts)
                       :data-running (:running counts) :data-completed (:completed counts)
                       :data-failed (:failed counts) :data-cancelled (:cancelled counts) :data-rejected (:rejected counts)}
   (if counts
     (str "Import previews: " (:pending counts) " pending · " (:running counts) " running · "
          (:completed counts) " completed · " (:failed counts) " failed · " (:cancelled counts) " cancelled · " (:rejected counts) " rejected")
     "Checking import previews…")])
