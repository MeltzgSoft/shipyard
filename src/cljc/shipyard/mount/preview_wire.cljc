(ns shipyard.mount.preview-wire
  "Little-endian line buffers: magic/version, three segment counts, then float32 XYZ pairs.
  The browser creates aligned typed views without rebuilding coordinate vectors.")

(def magic 1398361424)
(def version 1)
(def header-bytes 20)
(def groups [:cuts :mirror-cuts :border])
(def max-segments 1000000)

#?(:clj
   (defn encode [projection]
     (let [counts (mapv #(count (get projection %)) groups)
           size (+ header-bytes (* 24 (reduce + counts)))
           buffer (doto (java.nio.ByteBuffer/allocate size)
                    (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
       (when (> (reduce + counts) max-segments)
         (throw (ex-info "Mount preview exceeds line capacity." {})))
       (.putInt buffer magic)
       (.putInt buffer version)
       (doseq [n counts] (.putInt buffer n))
       (doseq [group groups line (get projection group) point line coordinate point]
         (when-not (Double/isFinite (double coordinate))
           (throw (ex-info "Mount preview contains a nonfinite coordinate." {})))
         (.putFloat buffer (float coordinate)))
       (.array buffer))))

(defn decode [bytes]
  (let [size #?(:clj (alength ^bytes bytes) :cljs (.-byteLength bytes))]
    (when (< size header-bytes)
      (throw (ex-info "Mount preview is truncated." {})))
    (let [view #?(:clj (doto (java.nio.ByteBuffer/wrap bytes) (.order java.nio.ByteOrder/LITTLE_ENDIAN))
                  :cljs (js/DataView. bytes))
          uint (fn [offset] #?(:clj (bit-and (.getInt ^java.nio.ByteBuffer view (int offset)) 0xffffffff)
                               :cljs (.getUint32 view offset true)))
          counts (mapv #(uint (+ 8 (* % 4))) (range 3))
          total (reduce + counts)]
      (when-not (and (= magic (uint 0)) (= version (uint 4))
                     (<= total max-segments) (= size (+ header-bytes (* 24 total))))
        (throw (ex-info "Mount preview format or length is invalid." {})))
      (loop [remaining (map vector groups counts) offset header-bytes result {}]
        (if-let [[group count] (first remaining)]
          (let [values #?(:clj (float-array (map (fn [i] (.getFloat ^java.nio.ByteBuffer view (int (+ offset (* i 4)))))
                                                 (range (* 6 count))))
                          :cljs (js/Float32Array. bytes offset (* 6 count)))]
            (recur (next remaining) (+ offset (* count 24)) (assoc result group values)))
          result)))))
