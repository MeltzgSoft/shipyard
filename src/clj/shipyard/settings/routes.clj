(ns shipyard.settings.routes
  (:require [shipyard.http.contracts :as contracts]
            [shipyard.settings.handlers :as handlers]))

(defn routes [deps]
  (into [["/settings/cuts" {:post {:handler (partial handlers/cuts! deps)
                                   :parameters {:form [:map [:pit-depth string?] [:pit-diameter string?]
                                                       [:recess-depth string?] [:recess-border string?]]}
                                   :responses contracts/html-responses}}]]
        (for [action [:add :rename :delete]]
          [(str "/settings/classifications/" (name action))
           {:post {:handler (partial handlers/classification! deps action)
                   :parameters {:form (cond-> [:map [:field [:enum "bundle" "class" "role"]] [:value string?]]
                                        (= action :rename) (conj [:new-value string?]))}
                   :responses contracts/html-responses}}])))
