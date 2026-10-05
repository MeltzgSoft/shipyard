(ns shipyard.mount.alignment
  "Optional undirected alignment lines in an authored mount plane."
  (:require [shipyard.math :as math]))

(def axes #{:horizontal :vertical})

(defn valid? [mount]
  (or (not (contains? mount :mount/alignment-axis))
      (contains? axes (:mount/alignment-axis mount))))

(defn direction
  "Source-space tangent for the chosen line; no axis means no constraint."
  [{:mount/keys [alignment-axis axis roll]}]
  (case alignment-axis
    :horizontal roll
    :vertical (math/cross axis roll)
    nil nil
    (throw (ex-info "Choose horizontal or vertical mount alignment, or clear it."
                    {:code :invalid-mount-alignment}))))

(defn line
  "A centered, undirected source-space segment for the viewport."
  [{:mount/keys [pos] :as mount} half-length]
  (when-let [tangent (direction mount)]
    [(math/subtract pos (math/scale half-length tangent))
     (math/add pos (math/scale half-length tangent))]))
