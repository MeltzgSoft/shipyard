(ns shipyard.mount.facet-input
  "Bounded parsing and validation for durable facet selections."
  (:require [clojure.edn :as edn]
            [malli.core :as m]
            [shipyard.domain.schemas :as schemas]))

(def ^:const max-indices 4096)
(def ^:private valid-indices? (m/validator schemas/facet-indices))

(defn parse-indices [value]
  (try
    (let [indices (edn/read-string value)]
      (when (valid-indices? indices)
        indices))
    (catch Exception _ nil)))

(defn valid-input? [value]
  (boolean (parse-indices value)))

(defn in-mesh? [mesh indices]
  (every? #(< % (quot (:index-count mesh) 3)) indices))
