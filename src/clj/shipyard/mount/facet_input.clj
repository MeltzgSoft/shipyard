(ns shipyard.mount.facet-input
  "Bounded parsing and validation for durable facet selections."
  (:require [clojure.edn :as edn]
            [malli.core :as m]))

(def ^:private valid-indices?
  (m/validator [:vector {:min 1 :max 4096} [:and integer? [:>= 0] [:<= 2147483647]]]))

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
