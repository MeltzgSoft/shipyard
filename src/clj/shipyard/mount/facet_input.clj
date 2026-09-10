(ns shipyard.mount.facet-input
  "Bounded parsing and validation for durable facet selections."
  (:require [clojure.edn :as edn]))

(def ^:const max-indices 4096)

(defn parse-indices [value]
  (try
    (let [indices (edn/read-string value)]
      (when (and (vector? indices)
                 (seq indices)
                 (<= (count indices) max-indices)
                 (every? #(and (integer? %) (<= 0 % Integer/MAX_VALUE)) indices))
        indices))
    (catch Exception _ nil)))

(defn valid-input? [value]
  (boolean (parse-indices value)))

(defn in-mesh? [mesh indices]
  (every? #(< % (quot (:index-count mesh) 3)) indices))
