(ns shipyard.fleet.export-routes
  (:require [shipyard.fleet.export-handlers :as handlers]
            [shipyard.http.contracts :as contracts]))

(defn routes [deps]
  [["/fleets/export" {:post {:handler (partial handlers/export! deps)
                             :parameters {:form [:map [:id [:and string? [:fn #(some? (parse-uuid %))]]]]}
                             :responses contracts/html-responses}}]])
