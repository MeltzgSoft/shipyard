(ns shipyard.paint.render
  "Sparse durable face colors projected onto one vertex-color buffer per instance."
  (:require ["three" :as three]
            [shipyard.paint.faces :as faces]
            [shipyard.scheme.material :as material]))

(defn triangle-count [^js geometry]
  (/ (if-let [index (.-index geometry)] (.-count index)
             (.. geometry -attributes -position -count)) 3))

(defn face-key [^js geometry triangle]
  (let [position (.getAttribute geometry "position") index (.-index geometry)]
    (faces/face-key
     (mapv (fn [corner]
             (let [offset (+ (* triangle 3) corner) vertex (if index (.getX index offset) offset)]
               [(.getX position vertex) (.getY position vertex) (.getZ position vertex)])) (range 3)))))

(defn set-details! [^js object layer]
  (let [valid? (and (= (:part-id layer) (.. object -userData -partId))
                    (= (:mesh-key layer) (.. object -userData -meshKey)))]
    (set! (.. object -userData -paintDetails) (when valid? layer))))

(defn projected-faces
  "Small-fixture E2E coordinates derived from actual geometry, never pick mocks."
  [^js object ^js camera ^js canvas]
  (let [geometry (.-geometry object) position (.getAttribute geometry "position") index (.-index geometry)]
    (when (<= (triangle-count geometry) 64)
      (mapv (fn [triangle]
              (let [[^js a ^js b ^js c] (mapv (fn [corner]
                                                (let [offset (+ (* triangle 3) corner) vertex (if index (.getX index offset) offset)]
                                                  (doto (three/Vector3.) (.fromBufferAttribute position vertex) (.applyMatrix4 (.-matrixWorld object))))) (range 3))
                    center (doto (.clone a) (.add b) (.add c) (.multiplyScalar (/ 1 3)))
                    normal (.cross (.sub (.clone b) a) (.sub (.clone c) a))
                    front? (pos? (.dot normal (.sub (.clone (.-position camera)) center)))
                    projected (.project center camera)]
                {:key (face-key geometry triangle) :front? front?
                 :x (* (+ 1 (.-x projected)) 0.5 (.-clientWidth canvas))
                 :y (* (- 1 (.-y projected)) 0.5 (.-clientHeight canvas))}))
            (range (triangle-count geometry))))))

(defn- face-index! [^js object]
  (or (.. object -userData -faceIndex)
      (let [geometry (.-geometry object) index (js/Map.)]
        (dotimes [triangle (triangle-count geometry)]
          (let [key (face-key geometry triangle) previous (.get index key)]
            (.set index key (cond (nil? previous) triangle
                                  (number? previous) #js [previous triangle]
                                  :else (do (.push previous triangle) previous)))))
        (set! (.. object -userData -faceIndex) index)
        index)))

(defn apply-colors! [^js object base colors?]
  (let [mask (:faces (.. object -userData -paintDetails))
        ^js surface (.-material object)
        enabled? (and (not colors?) (seq mask))]
    (when (not= (boolean enabled?) (.-vertexColors surface))
      (set! (.-vertexColors surface) (boolean enabled?))
      (set! (.-needsUpdate surface) true))
    (when enabled?
      (when (.. object -geometry -index)
        (let [old (.-geometry object)]
          (set! (.-geometry object) (.toNonIndexed old))
          (.dispose old)))
      (let [geometry (.-geometry object)
            index (face-index! object)
            attribute (or (.getAttribute geometry "color")
                          (let [value (three/BufferAttribute. (js/Float32Array. (* 9 (triangle-count geometry))) 3)]
                            (.setAttribute geometry "color" value) value))
            signature [base mask]
            [old-base old-mask] (.. object -userData -colorSignature)]
        ;; Pointer moves with the same mask need no allocation or buffer upload.
        (when (not= signature (.. object -userData -colorSignature))
          (when (not= base old-base)
            (let [[r g b] (mapv material/srgb->linear base)]
              (dotimes [vertex (.-count attribute)] (.setXYZ attribute vertex r g b))))
          (doseq [key (keys (if (= base old-base) (merge old-mask mask) mask))
                  :when (or (not= base old-base) (not= (get old-mask key) (get mask key)))]
            (let [entry (.get index key) [r g b] (mapv material/srgb->linear (get mask key base))]
              (doseq [triangle (if (number? entry) [entry] (array-seq entry))]
                (dotimes [corner 3] (.setXYZ attribute (+ (* triangle 3) corner) r g b)))))
          (set! (.-needsUpdate attribute) true)
          (set! (.. object -userData -colorSignature) signature))
        (.setRGB (.-color surface) 1 1 1)))))
