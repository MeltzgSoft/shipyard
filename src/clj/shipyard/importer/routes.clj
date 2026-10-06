(ns shipyard.importer.routes
  (:require [shipyard.http.contracts :as contracts]
            [shipyard.importer.handlers :as handlers]
            [shipyard.part-browser.handlers :as thumbnails]))

(defn routes [deps]
  [["/imports/progress" {:get {:handler (partial handlers/progress! deps) :responses contracts/html-responses}}]
   ["/imports/thumbnails/:file" {:get {:handler (partial thumbnails/file-thumbnail! deps)
                                       :parameters {:path [:map [:file string?]]}
                                       :responses contracts/html-responses}}]
   ["/imports/choose" {:post {:handler (partial handlers/choose! deps)
                              :responses contracts/html-responses}}]
   ["/imports/group" {:post {:handler #(handlers/edit-group! deps % :group)
                             :parameters {:form [:map [:name {:optional true} string?]]}
                             :responses contracts/html-responses}}]
   ["/imports/split" {:post {:handler #(handlers/edit-group! deps % :split)
                             :parameters {:form [:map [:group string?]]}
                             :responses contracts/html-responses}}]
   ["/imports/variant" {:post {:handler #(handlers/edit-group! deps % :variant)
                               :parameters {:form [:map [:file string?]
                                                   [:variant [:enum "supported" "unsupported" "unsupported-pitted"]]]}
                               :responses contracts/html-responses}}]
   ["/imports/selection" {:post {:handler (partial handlers/selection! deps)
                                 :parameters {:form [:map [:selection [:enum "all" "none"]]]}
                                 :responses contracts/html-responses}}]
   ["/imports/start" {:post {:handler (partial handlers/start! deps)
                             :parameters {:form [:map [:archive string?]]}
                             :responses contracts/html-responses}}]
   ["/imports/cancel" {:post {:handler (partial handlers/cancel! deps) :responses contracts/html-responses}}]
   ["/imports/commit" {:post {:handler (partial handlers/commit! deps) :responses contracts/html-responses}}]])
