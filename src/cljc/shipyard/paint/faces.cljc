(ns shipyard.paint.faces
  "Stable source-space triangle identity and sparse color masks."
  (:require [malli.core :as m]
            [shipyard.domain.schemas :as schemas]))

#?(:clj
   (defn- float-hex [value]
     (let [value (if (zero? value) 0.0 value)
           hex (Integer/toHexString (Float/floatToIntBits (float value)))]
       (str (subs "00000000" (count hex)) hex))))

(defn face-key
  "Canonical Float32 vertex rotation: stable indices, distinct opposite faces."
  [vertices]
  (let [[a b c] vertices
        ordered (first (sort [[a b c] [b c a] [c a b]]))]
    #?(:clj (apply str (mapcat #(map float-hex %) ordered))
       :cljs (let [buffer (js/Float32Array. 1) bits (js/Uint32Array. (.-buffer buffer))]
               ;; One scratch buffer per triangle, not nine buffers and views.
               (apply str (for [vertex ordered value vertex]
                            (do (aset buffer 0 (if (zero? value) 0 value))
                                (.padStart (.toString (aget bits 0) 16) 8 "0"))))))))

(def key? (m/validator schemas/face-key))
(def paint? (m/validator schemas/detail-material))

(defn resolve-material
  "A detail material overrides the inherited material's channels."
  [inherited detail]
  (if (map? detail) detail inherited))

(defn stroke [layer part-id mesh-key keys paint erase?]
  (cond
    (not (schemas/valid-face-keys? keys)) {:error :invalid-faces}
    (not (paint? paint)) {:error :invalid-material}
    (and layer (or (not= part-id (:part-id layer)) (not= mesh-key (:mesh-key layer)))) {:error :changed-source}
    :else (let [result {:part-id part-id :mesh-key mesh-key
                        :faces (if erase? (apply dissoc (:faces layer) keys)
                                   (reduce #(assoc %1 %2 paint) (or (:faces layer) {}) keys))}
                result (update result :faces #(or % {}))]
            {:layer result})))
