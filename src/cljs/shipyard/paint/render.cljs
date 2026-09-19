(ns shipyard.paint.render
  "Sparse durable face materials projected onto vertex buffers, without extra draws."
  (:require ["three" :as three]
            [shipyard.paint.faces :as faces]
            [shipyard.paint.shader :as shader]
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

(defn- install-finish! [^js surface]
  (or (.. surface -userData -finishEnabled)
      (let [enabled #js {:value false}]
        (set! (.. surface -userData -finishEnabled) enabled)
        (set! (.-onBeforeCompile surface)
              (fn [^js program _renderer]
                (let [{:keys [vertex fragment]} (shader/with-finish (.-vertexShader program) (.-fragmentShader program))]
                  (set! (.. program -uniforms -shipyardFinishEnabled) enabled)
                  (set! (.-vertexShader program) vertex)
                  (set! (.-fragmentShader program) fragment)
                  (set! (.. surface -userData -finishCompiled) true))))
        (set! (.-customProgramCacheKey surface) (fn [] "shipyard-face-finish-v1"))
        (set! (.-needsUpdate surface) true)
        enabled)))

(defn apply-details! [^js object inherited colors?]
  (let [inherited (select-keys inherited [:base :metalness :roughness])
        mask (:faces (.. object -userData -paintDetails))
        ^js surface (.-material object)
        enabled? (and (not colors?) (seq mask))]
    (when (not= (boolean enabled?) (.-vertexColors surface))
      (set! (.-vertexColors surface) (boolean enabled?))
      (set! (.-needsUpdate surface) true))
    (when-let [uniform (.. surface -userData -finishEnabled)]
      (set! (.-value uniform) (boolean (seq mask))))
    (when (seq mask)
      (when (.. object -geometry -index)
        (let [old (.-geometry object)]
          (set! (.-geometry object) (.toNonIndexed old))
          (.dispose old)))
      (let [geometry (.-geometry object)
            index (face-index! object)
            attribute (or (.getAttribute geometry "color")
                          (let [value (three/BufferAttribute. (js/Float32Array. (* 9 (triangle-count geometry))) 3)]
                            (.setAttribute geometry "color" value) value))
            finish (or (.getAttribute geometry "shipyardFinish")
                       (let [value (three/BufferAttribute. (js/Float32Array. (* 6 (triangle-count geometry))) 2)]
                         (.setAttribute geometry "shipyardFinish" value) value))
            uniform (install-finish! surface)
            signature [inherited mask]
            [old-inherited old-mask] (.. object -userData -detailSignature)]
        (set! (.-value uniform) true)
        ;; Pointer moves with the same mask need no allocation or buffer upload.
        (when (or (.. object -userData -paintDirtyFaces) (not= signature (.. object -userData -detailSignature)))
          (when (not= inherited old-inherited)
            (let [[r g b] (mapv material/srgb->linear (:base inherited))]
              (dotimes [vertex (.-count attribute)]
                (.setXYZ attribute vertex r g b)
                (.setXY finish vertex (:metalness inherited) (:roughness inherited)))))
          (doseq [key (if (and (= inherited old-inherited) (.. object -userData -paintDirtyFaces))
                        (.. object -userData -paintDirtyFaces)
                        (keys (if (= inherited old-inherited) (merge old-mask mask) mask)))
                  :when (or (not= inherited old-inherited) (not= (get old-mask key) (get mask key)))]
            (let [entry (.get index key)
                  {:keys [base metalness roughness]} (faces/resolve-material inherited (get mask key))
                  [r g b] (mapv material/srgb->linear base)]
              (doseq [triangle (if (number? entry) [entry] (array-seq entry))]
                (dotimes [corner 3]
                  (let [vertex (+ (* triangle 3) corner)]
                    (.setXYZ attribute vertex r g b)
                    (.setXY finish vertex metalness roughness))))))
          (set! (.-needsUpdate attribute) true)
          (set! (.-needsUpdate finish) true)
          (set! (.. object -userData -detailSignature) signature))
        (when enabled? (.setRGB (.-color surface) 1 1 1))))
    (set! (.. object -userData -paintDirtyFaces) nil)))
