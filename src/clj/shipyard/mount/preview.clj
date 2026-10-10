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

(defn selection
  "Reusable source-space boundary and border geometry, independent of cut controls."
  [mesh indices border-indices opts]
  (when-not (and (or (nil? indices) (facet-input/in-facet? mesh indices opts))
                 (or (nil? border-indices) (facet-input/in-facet? mesh border-indices opts)))
    (throw (ex-info "Choose a nonempty selection from one current mount face." {})))
  {:outline (when indices (cut/outline (geometry/mesh-triangles mesh indices)))
   :border (if border-indices (border-lines mesh border-indices) [])})

(defn from-selection
  "Apply inexpensive frame controls and physical offset profiles to a prepared boundary."
  [{:keys [outline border]} {:keys [frame cut capacity direction mirror part-orientation]}]
  (when-not (wizard/valid-frame? frame)
    (throw (ex-info "Choose a valid current mount frame." {})))
  (let [outline (or outline (:mount/outline frame))
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
     :border border}))

(defn draft
  "Derive a source-space preview without writing mounts, facets, or generated files."
  [mesh {:keys [indices border-indices] :as value} opts]
  (from-selection (selection mesh indices border-indices opts) value))
