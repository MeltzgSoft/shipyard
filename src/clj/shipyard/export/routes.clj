(ns shipyard.export.routes
  (:require [shipyard.export.handlers :as handlers]
            [shipyard.http.contracts :as contracts]))

(defn routes [deps]
  [["/ships/export/clear" {:post {:handler (partial handlers/clear! deps) :responses contracts/html-responses}}]
   ["/ships/export/status" {:get {:handler (partial handlers/status! deps) :responses contracts/html-responses}}]
   ["/ships/export/topology" {:get {:handler (partial handlers/topology! deps)}}]
   ["/ships/export" {:get {:handler (partial handlers/current! deps) :responses contracts/html-responses}
                     :post {:handler (partial handlers/update! deps)
                            :parameters {:form [:map [:directory [:string {:max 4096}]]
                                                [:diameter {:optional true} [:string {:max 20}]]
                                                [:move {:optional true} [:re #"(unsupported-pitted|unsupported|supported):(up|down)"]]
                                                [:preview {:optional true} [:string {:min 1 :max 2048}]]
                                                [:action {:optional true} [:enum "export" "review"]]]}
                            :responses contracts/html-responses}}]
   ["/ships/export/selection" {:post {:handler (partial handlers/selection! deps)
                                      :parameters {:form [:map [:kind [:enum "class"]]
                                                          [:id [:and string? [:fn #(some? (parse-uuid %))]]]
                                                          [:selected [:enum "true" "false"]]]}
                                      :responses contracts/html-responses}}]
   ["/ships/export/download" {:get {:handler (partial handlers/download! deps)}}]])
