(ns shipyard.mount.alignment
  "Optional directed alignment axes in an authored mount plane."
  (:require [shipyard.math :as math]))

(def axes #{:horizontal :horizontal-negative :vertical :vertical-negative})

(defn valid? [mount]
  (or (not (contains? mount :mount/alignment-axis))
      (contains? axes (:mount/alignment-axis mount))))

(defn direction
  "Source-space tangent for the chosen arrow; no axis means no constraint."
  [{:mount/keys [alignment-axis axis roll]}]
  (case alignment-axis
    :horizontal roll
    :horizontal-negative (math/scale -1.0 roll)
    :vertical (math/cross axis roll)
    :vertical-negative (math/scale -1.0 (math/cross axis roll))
    nil nil
    (throw (ex-info "Choose a signed horizontal or vertical mount alignment, or clear it."
                    {:code :invalid-mount-alignment}))))

(defn line
  "Source-space arrow endpoints, ordered from tail to tip."
  [{:mount/keys [pos] :as mount} half-length]
  (when-let [tangent (direction mount)]
    [(math/subtract pos (math/scale half-length tangent))
     (math/add pos (math/scale half-length tangent))]))

(defn mirrored-axis
  "Reflect the directed arrow while keeping the mirrored frame right-handed."
  [axis]
  (case axis
    :vertical :vertical-negative
    :vertical-negative :vertical
    axis))
