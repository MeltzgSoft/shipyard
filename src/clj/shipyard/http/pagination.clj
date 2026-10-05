(ns shipyard.http.pagination
  "Incremental list batches and their HTMX navigation."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [shipyard.thumbnail.views :as thumbnails]))

(def page-size 50)

(def filter-keys ["bundle" "class" "role" "orientation" "variant" "q"
                  "has-unsupported" "has-supported" "has-pitted"])

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
      :hx-params (str/join "," (into filter-keys ["page" "chunk"]))
      :hx-sync (str include ":abort")
      :hx-vals (json/write-str {"page" (str (inc page)) "chunk" "1"})}
     [:button {:type "button"} "Load more"]
     [:span.htmx-indicator "Loading…"]]))

(defn progress []
  (thumbnails/progress))

(defn window [items requested]
  (let [items (vec items)
        pages (max 1 (long (Math/ceil (/ (count items) page-size))))
        page (min pages (max 1 (or (some-> requested (str) (parse-long)) 1)))
        start (* (dec page) page-size)]
    {:items (subvec items start (min (count items) (+ start page-size)))
     :page page :pages pages :total (count items)}))

(defn controls [{:keys [page pages total]} url target include]
  (when (> pages 1)
    [:nav.table-pages {:aria-label "Table pages"}
     (for [[label n disabled?] [["Previous" (dec page) (= page 1)] ["Next" (inc page) (= page pages)]]]
       [:button {:type "button" :disabled disabled? :hx-get url :hx-target target :hx-swap "outerHTML"
                 :hx-include include :hx-sync "#detail:queue last"
                 :hx-vals (json/write-str {"page" (str n) "table-scroll" "0"})} label])
     [:span (str "Page " page " of " pages " · " total " rows")]]))
