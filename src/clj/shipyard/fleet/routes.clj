(ns shipyard.fleet.routes
  (:require [shipyard.fleet.handlers :as handlers]
            [shipyard.http.contracts :as contracts]))

(defn routes [deps]
  [["/fleets/edit" {:post {:handler (partial handlers/edit! deps)
                           :parameters {:form [:map [:id string?] [:action [:enum "rename" "class-set" "class-remove" "extra-set" "extra-remove" "variant" "default" "delete"]]
                                               [:variant {:optional true} [:enum "unsupported" "supported" "unsupported-pitted"]]
                                               [:direction {:optional true} [:enum "up" "down"]]]}
                           :responses contracts/html-responses}}]
   ["/fleets/preview" {:post {:handler (partial handlers/preview! deps)
                              :parameters {:form [:map [:id string?] [:class-id string?]]}
                              :responses contracts/html-responses}}]
   ["/fleets" {:get {:handler (partial handlers/current! deps) :responses contracts/html-responses}}]
   ["/fleets/open" {:post {:handler (partial handlers/open! deps)
                           :parameters {:form [:map [:id [:and string? [:fn #(some? (parse-uuid %))]]]]}
                           :responses contracts/html-responses}}]
   ["/fleets/create" {:post {:handler (partial handlers/create! deps)
                             :parameters {:form [:map [:name string?]]}
                             :responses contracts/html-responses}}]])
