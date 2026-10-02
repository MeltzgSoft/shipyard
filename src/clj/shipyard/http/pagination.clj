(ns shipyard.http.pagination
  "Incremental list batches and their HTMX navigation."
  (:require [clojure.data.json :as json]))

(def page-size 50)

(def filter-keys ["bundle" "class" "role" "orientation" "q"])

(defn same-filters? [a b]
  (= (map #(get a % "") filter-keys) (map #(get b % "") filter-keys)))

(defn batch-window
  "A request loads one batch; restoring a list restores its loaded prefix."
  [items requested chunk?]
  (let [items (vec items)
        pages (max 1 (quot (+ (count items) (dec page-size)) page-size))
        page (min (if chunk? (inc pages) pages) (max 1 (or (some-> requested (str) (parse-long)) 1)))
        end (min (count items) (* page page-size))
        start (if chunk? (min end (* (dec page) page-size)) 0)]
    {:items (subvec items start end) :page page :total (count items)
     :more? (< end (count items)) :loaded end}))

(defn more [{:keys [page more?]} url root include]
  (when more?
    [:div.list-more
     {:hx-get url :hx-trigger (str "intersect once root:" root ", click")
      :hx-target "this" :hx-swap "outerHTML" :hx-include include
      :hx-params "bundle,class,role,orientation,q,page,chunk"
      :hx-sync (str include ":abort")
      :hx-vals (json/write-str {"page" (str (inc page)) "chunk" "1"})}
     [:button {:type "button"} "Load more"]
     [:span.htmx-indicator "Loading…"]]))

(defn progress []
  [:p.thumbnail-progress {:role "status" :data-thumbnail-progress true :aria-live "polite"}
   "Thumbnails load as you scroll."])
