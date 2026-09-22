(ns shipyard.paint.faces
  "Stable source-space triangle identity and sparse color masks.")

(defn- float-hex [value]
  (let [value (if (zero? value) 0.0 value)]
    #?(:clj (let [hex (Integer/toHexString (Float/floatToIntBits (float value)))]
              (str (subs "00000000" (count hex)) hex))
       :cljs (let [buffer (js/ArrayBuffer. 4) view (js/DataView. buffer)]
               (.setFloat32 view 0 value)
               (.padStart (.toString (.getUint32 view 0) 16) 8 "0")))))

(defn face-key
  "Canonical Float32 vertex rotation: stable indices, distinct opposite faces."
  [vertices]
  (let [[a b c] vertices
        ordered (first (sort [[a b c] [b c a] [c a b]]))]
    (apply str (mapcat #(map float-hex %) ordered))))

(defn key? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{72}" value))))

(defn rgb? [value]
  (and (vector? value) (= 3 (count value))
       (every? #(and (number? %) (<= 0 % 1)) value)))

(defn layer? [value]
  (and (map? value) (= #{:part-id :mesh-key :faces} (set (keys value)))
       (string? (:part-id value)) (<= 1 (count (:part-id value)) 2048)
       (string? (:mesh-key value)) (boolean (re-matches #"[0-9a-f]{64}" (:mesh-key value)))
       (map? (:faces value))
       (every? (fn [[key rgb]] (and (key? key) (rgb? rgb))) (:faces value))))

(defn stroke [layer part-id mesh-key keys rgb erase?]
  (cond
    (not (and (vector? keys) (seq keys) (every? key? keys))) {:error :invalid-faces}
    (not (rgb? rgb)) {:error :invalid-color}
    (and layer (or (not= part-id (:part-id layer)) (not= mesh-key (:mesh-key layer)))) {:error :changed-source}
    :else (let [result {:part-id part-id :mesh-key mesh-key
                        :faces (if erase? (apply dissoc (:faces layer) keys)
                                   (reduce #(assoc %1 %2 rgb) (or (:faces layer) {}) keys))}
                result (update result :faces #(or % {}))]
            {:layer result})))
