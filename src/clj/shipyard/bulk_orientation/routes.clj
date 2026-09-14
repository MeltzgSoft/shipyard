(ns shipyard.bulk-orientation.routes
  "Malli-enforced routes for the bulk orientation workspace."
  (:require [shipyard.bulk-orientation.handlers :as handlers]
            [shipyard.http.contracts :as contracts]))

(defn routes [deps]
  [["/orient" {:get {:handler (partial handlers/orient! deps)
                     :responses contracts/html-responses}}]
   ["/orient/parts" {:get {:handler (partial handlers/parts! deps)
                           :parameters {:query contracts/orientation-query}
                           :responses contracts/html-responses}}]
   ["/orient/render" {:post {:handler (partial handlers/render! deps)
                             :parameters {:form contracts/bulk-render-form}
                             :responses contracts/html-responses}}]
   ["/orient/save" {:post {:handler (partial handlers/save! deps)
                           :parameters {:form contracts/bulk-save-form}
                           :responses contracts/html-responses}}]])
