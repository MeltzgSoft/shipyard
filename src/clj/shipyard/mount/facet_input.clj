(ns shipyard.mount.facet-input
  "Bounded parsing and validation for durable facet selections."
  (:require [clojure.edn :as edn]
            [malli.core :as m]
            [shipyard.mesh.facet :as facet]
            [shipyard.math :as math]
            [shipyard.settings.transforms :as settings]))

(def indices-schema
  [:vector {:min 1} [:and integer? [:>= 0] [:<= 2147483647]]])

(def ^:private valid-indices? (m/validator indices-schema))

(defn parse-indices [value]
  (try
    (let [indices (edn/read-string value)]
      (when (and (valid-indices? indices) (= (count indices) (count (set indices))))
        indices))
    (catch Exception _ nil)))

(defn valid-input? [value]
  (boolean (parse-indices value)))

(defn in-mesh? [mesh indices]
  (every? #(< % (quot (:index-count mesh) 3)) indices))

(defn valid-angle? [value]
  (let [angle (math/parse-finite-double value)]
    (boolean (and angle (<= 0 angle 90)))))

(defn valid-seam? [value]
  (settings/valid-seam? (math/parse-finite-double value)))

(defn selection-options [base params]
  (let [angle (or (:face-angle params) (get params "face-angle"))
        rule (or (:face-rule params) (get params "face-rule"))
        seam (or (:face-seam params) (get params "face-seam"))
        seed (or (:face-seed params) (get params "face-seed"))]
    (cond-> base
      (some? rule) (assoc :selection-rule (keyword rule))
      (some? seam) (assoc :seam-tolerance-mm (math/parse-finite-double seam))
      (some? angle) (assoc :selection-angle-deg (math/parse-finite-double angle))
      (some? seed) (assoc :selection-seed (if (integer? seed) seed (parse-long seed))))))

(defn in-facet?
  "Accept a nonempty subset of one authoritative connected selection, retaining its original angular seed."
  [mesh indices opts]
  (try
    (and (seq indices) (in-mesh? mesh indices)
         (let [allowed (set (:facet-indices (facet/select mesh (or (:selection-seed opts) (first indices)) opts)))]
           (every? allowed indices)))
    (catch clojure.lang.ExceptionInfo _ false)))
