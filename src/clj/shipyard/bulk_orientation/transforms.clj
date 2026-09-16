(ns shipyard.bulk-orientation.transforms
  "Pure selection and save-request decisions for bulk orientation."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [shipyard.part.orientation :as orientation]))

(defn saved? [part]
  (boolean (orientation/normalize-quaternion (:part/orientation part))))

(defn matches-orientation? [state part]
  (case state
    (nil "all") true
    "saved" (saved? part)
    "unset" (not (saved? part))
    false))

(defn selected-ids [value]
  (try
    (let [ids (edn/read-string (or value "[]"))]
      (when (and (vector? ids) (every? string? ids))
        (vec (distinct ids))))
    (catch Exception _ nil)))

(defn orientations-request [params]
  (try
    (let [values (edn/read-string (or (get params "orientations") "{}"))]
      (when (and (map? values) (seq values))
        (let [orientations (into {}
                                 (keep (fn [[part-id value]]
                                         (when (and (string? part-id)
                                                    (orientation/normalize-quaternion value))
                                           [part-id (orientation/orientation-of value)])))
                                 values)]
          (when (= (count values) (count orientations)) orientations))))
    (catch Exception _ nil)))

(defn selection-after-change
  "Replace the visible selection while retaining selected parts hidden by filters."
  [previous visible selected]
  (let [visible (set visible)]
    (vec (sort (set/union (set/difference (set previous) visible)
                          (set/intersection visible (set selected)))))))
