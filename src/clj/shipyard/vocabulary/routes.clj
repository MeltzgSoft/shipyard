(ns shipyard.vocabulary.routes
  (:require [shipyard.http.contracts :as contracts]
            [shipyard.vocabulary.handlers :as handlers]))

(defn routes [deps]
  [["/classifications" {:post {:handler (partial handlers/add! deps)
                               :parameters {:form [:map [:field [:enum "bundle" "class" "role"]] [:value string?]]}
                               :responses contracts/html-responses}}]])
