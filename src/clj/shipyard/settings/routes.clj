(ns shipyard.settings.routes
  (:require [shipyard.http.contracts :as contracts]
            [shipyard.settings.handlers :as handlers]))

(defn routes [deps]
  (into [["/settings/export-root" {:post {:handler (partial handlers/export-root! deps)
                                          :parameters {:form [:map [:export-root string?]]}
                                          :responses contracts/html-responses}}]
         ["/settings/variants" {:post {:handler (partial handlers/variant! deps)
                                       :parameters {:form [:map [:variant [:enum "unsupported" "supported" "unsupported-pitted"]]
                                                           [:direction [:enum "up" "down"]]]}
                                       :responses contracts/html-responses}}]
         ["/settings/theme" {:post {:handler (partial handlers/theme! deps)
                                    :parameters {:form [:map [:theme [:enum "light" "dark" "auto"]]]}
                                    :responses contracts/html-responses}}]
         ["/settings/cuts" {:post {:handler (partial handlers/cuts! deps)
                                   :parameters {:form [:map [:pit-depth string?] [:pit-diameter string?]
                                                       [:recess-depth string?] [:recess-border string?]]}
                                   :responses contracts/html-responses}}]]
        (for [action [:add :rename :delete]]
          [(str "/settings/classifications/" (name action))
           {:post {:handler (partial handlers/classification! deps action)
                   :parameters {:form (cond-> [:map [:field [:enum "bundle" "class" "role"]] [:value string?]]
                                        (= action :rename) (conj [:new-value string?]))}
                   :responses contracts/html-responses}}])))
