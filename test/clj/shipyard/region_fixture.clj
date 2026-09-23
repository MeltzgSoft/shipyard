(ns shipyard.region-fixture
  (:require [shipyard.catalog.db :as catalog]
            [shipyard.regions.registry :as registry]))

(defn id [cat name]
  (first (keep (fn [[id entry]] (when (= name (:name entry)) id))
               (registry/definitions (catalog/region-registry (catalog/snapshot! cat))))))

(defn names [cat]
  (let [shared (catalog/region-registry (catalog/snapshot! cat))]
    (mapv #(get-in (registry/definitions shared) [% :name]) (registry/ids shared))))
