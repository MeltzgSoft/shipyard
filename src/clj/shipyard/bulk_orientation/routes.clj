(ns shipyard.bulk-orientation.routes
  "Malli-enforced routes for the bulk orientation workspace."
  (:require [shipyard.bulk-orientation.handlers :as handlers]
            [shipyard.http.contracts :as contracts]
            [shipyard.part-browser.handlers :as browser]
            [shipyard.workspace.handlers :as workspace]))

(defn routes [deps]
  [["/thumbnails/*id" {:get {:handler (partial browser/thumbnail! deps)
                             :parameters {:path contracts/part-path} :responses contracts/html-responses}}]
   ["/parts/metadata" {:post {:handler (partial handlers/metadata! deps)
                              :parameters {:form [:map [:field [:enum "bundle" "class" "role" "name" "variant"]]
                                                  [:operation [:enum "set" "replace" "prefix" "suffix"]]
                                                  [:value string?] [:find {:optional true} string?]]}
                              :responses contracts/html-responses}}]
   ["/orient/selection" {:post {:handler (partial handlers/selection! deps)
                                :parameters {:form [:map [:visible string?]
                                                    [:selected {:optional true} [:or string? [:vector string?]]]]}
                                :responses contracts/html-responses}}]
   ["/orient/select-all" {:post {:handler (partial handlers/select-all! deps)
                                 :parameters {:form [:map [:selection [:enum "all" "none"]]]}
                                 :responses contracts/html-responses}}]
   ["/orient" {:get {:handler (partial handlers/orient! deps)
                     :responses contracts/html-responses}}]
   ["/orient/parts" {:get {:handler (partial handlers/parts! deps)
                           :parameters {:query contracts/orientation-query}
                           :responses contracts/html-responses}}]
   ["/orient/render" {:post {:handler (partial workspace/render-orient! deps)
                             :parameters {:form contracts/bulk-render-form}
                             :responses contracts/html-responses}}]
   ["/orient/save" {:post {:handler (partial handlers/save! deps)
                           :parameters {:form contracts/bulk-save-form}
                           :responses contracts/html-responses}}]])
