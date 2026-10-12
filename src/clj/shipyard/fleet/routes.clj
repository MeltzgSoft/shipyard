(ns shipyard.fleet.routes
  (:require [shipyard.fleet.handlers :as handlers]
            [shipyard.http.contracts :as contracts]))

(defn routes [deps]
  [["/fleets" {:get {:handler (partial handlers/current! deps) :responses contracts/html-responses}}]
   ["/fleets/open" {:post {:handler (partial handlers/open! deps)
                           :parameters {:form [:map [:id [:and string? [:fn #(some? (parse-uuid %))]]]]}
                           :responses contracts/html-responses}}]
   ["/fleets/create" {:post {:handler (partial handlers/create! deps)
                             :parameters {:form [:map [:name string?]]}
                             :responses contracts/html-responses}}]])
