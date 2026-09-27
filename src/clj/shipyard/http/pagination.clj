(ns shipyard.http.pagination
  "Bounded table windows and their HTMX navigation."
  (:require [clojure.data.json :as json]))

(def page-size 50)

(defn window [items requested]
  (let [items (vec items)
        pages (max 1 (long (Math/ceil (/ (count items) page-size))))
        page (min pages (max 1 (or (some-> requested str parse-long) 1)))
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
