(ns shipyard.importer.routes
  (:require [shipyard.http.contracts :as contracts]
            [shipyard.importer.handlers :as handlers]))

(defn routes [deps]
  [["/imports/selection" {:post {:handler (partial handlers/selection! deps)
                                 :parameters {:form [:map [:selection [:enum "all" "none"]]]}
                                 :responses contracts/html-responses}}]
   ["/imports/start" {:post {:handler (partial handlers/start! deps)
                             :parameters {:form [:map [:archive string?]]}
                             :responses contracts/html-responses}}]
   ["/imports/cancel" {:post {:handler (partial handlers/cancel! deps) :responses contracts/html-responses}}]
   ["/imports/commit" {:post {:handler (partial handlers/commit! deps) :responses contracts/html-responses}}]])
