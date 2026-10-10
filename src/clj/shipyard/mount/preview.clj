(ns shipyard.mount.preview
  "Pure draft geometry. Previews and durable subtraction share JTS profiles."
  (:require [shipyard.mount.cut :as cut]
            [shipyard.mount.facet-input :as facet-input]
            [shipyard.mount.split :as split]
            [shipyard.mount.wizard :as wizard]
            [shipyard.part.orientation :as orientation]
            [shipyard.pitting.geometry :as geometry]))

(defn lines [mount]
  (vec (mapcat (fn [{:keys [frame rings]}]
                 (cut/wire-lines frame rings (get-in mount [:mount/cut :depth])))
               (geometry/profiles mount))))

(defn border-lines [mesh indices]
  (->> (geometry/mesh-triangles mesh indices)
       (mapcat (fn [[a b c]] [[a b] [b c] [c a]]))
       (map #(vec (sort %)))
       (distinct)
       (vec)))

(defn draft
  "Derive a source-space preview without writing mounts, facets, or generated files."
  [mesh {:keys [frame indices border-indices cut capacity direction mirror part-orientation]} opts]
  (when-not (and (wizard/valid-frame? frame)
                 (or (nil? indices) (facet-input/in-facet? mesh indices opts))
                 (or (nil? border-indices) (facet-input/in-facet? mesh border-indices opts)))
    (throw (ex-info "Choose a nonempty selection from one current mount face." {})))
  (let [outline (if indices
                  (cut/outline (geometry/mesh-triangles mesh indices))
                  (:mount/outline frame))
        _ (when (and cut (not (seq outline)))
            (throw (ex-info "The selected triangles do not have a supported boundary." {})))
        mount (cond-> (assoc frame :mount/capacity capacity)
                outline (assoc :mount/outline outline)
                cut (assoc :mount/cut cut)
                (> capacity 1) (assoc :mount/split (split/metadata-for (assoc frame :face-points (vec (mapcat identity outline))) frame direction)))
        mirrored (when mirror
                   (wizard/mirror-mount mount (:plane mirror) (:offset mirror) :preview-mirror
                                        (or part-orientation orientation/identity-quaternion)))]
    {:cuts (if cut (lines mount) [])
     :mirror-cuts (if (and cut mirrored) (lines mirrored) [])
     :border (if border-indices (border-lines mesh border-indices) [])}))
