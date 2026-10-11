(ns shipyard.paint.render
  "Sparse durable face materials projected onto vertex buffers, without extra draws."
  (:require ["three" :as three]
            [shipyard.paint.faces :as faces]
            [shipyard.paint.shader :as shader]
            [shipyard.paint.topology :as topology]
            [shipyard.scheme.material :as material]))

(defn triangle-count [^js geometry]
  (/ (if-let [index (.-index geometry)] (.-count index)
             (.. geometry -attributes -position -count)) 3))

(defn triangle-points [^js geometry triangle]
  (let [position (.getAttribute geometry "position") index (.-index geometry)]
    (mapv (fn [corner]
            (let [offset (+ (* triangle 3) corner) vertex (if index (.getX index offset) offset)]
              [(.getX position vertex) (.getY position vertex) (.getZ position vertex)])) (range 3))))

(defn face-key [^js geometry triangle]
  (let [owner (or (.. geometry -userData -paintTopology) (.-userData geometry))
        keys (or (.-paintFaceKeys owner)
                 (let [keys (js/Array. (triangle-count geometry))]
                   (set! (.-paintFaceKeys owner) keys)
                   keys))]
    (or (aget keys triangle)
        (aset keys triangle (if-let [prepared (.. geometry -userData -preparedTopology)]
                              (topology/face-key prepared triangle)
                              (faces/face-key (triangle-points geometry triangle)))))))

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
                 :base (when-let [color (.getAttribute geometry "color")]
                         [(.getX color (* 3 triangle)) (.getY color (* 3 triangle)) (.getZ color (* 3 triangle))])
                 :x (* (+ 1 (.-x projected)) 0.5 (.-clientWidth canvas))
                 :y (* (- 1 (.-y projected)) 0.5 (.-clientHeight canvas))}))
            (range (triangle-count geometry))))))

(defn- face-index! [^js object]
  (let [owner (or (.. object -geometry -userData -paintTopology) (.-userData object))]
    (or (when-let [prepared (.. object -geometry -userData -preparedTopology)]
          #js {:get (fn [key] (clj->js (topology/triangles prepared key)))})
        (.-faceIndex owner)
        (let [geometry (.-geometry object) index (js/Map.)]
          (dotimes [triangle (triangle-count geometry)]
            (let [key (face-key geometry triangle) previous (.get index key)]
              (.set index key (cond (nil? previous) triangle
                                    (number? previous) #js [previous triangle]
                                    :else (do (.push previous triangle) previous)))))
          (set! (.-faceIndex owner) index)
          index))))

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
        (set! (.-customProgramCacheKey surface) (fn [] "shipyard-face-finish-v2"))
        (set! (.-needsUpdate surface) true)
        enabled)))

(defn set-regions! [^js object regions layers]
  (set! (.. object -userData -paintRegions)
        (when (= (:mesh-key regions) (.. object -userData -meshKey)) regions))
  (set! (.. object -userData -paintLayers) layers))

(defn- projected-mask! [^js object inherited]
  (let [regions (.. object -userData -paintRegions) layers (.. object -userData -paintLayers)
        signature [regions layers inherited]
        base (if (= signature (.. object -userData -regionSignature))
               (.. object -userData -regionMask)
               (let [mask (when (seq layers)
                            (into {} (map (fn [[key name]] [key (or (get layers name) inherited)])) (:faces regions)))]
                 (set! (.. object -userData -regionSignature) signature)
                 (set! (.. object -userData -regionMask) mask)
                 mask))]
    (reduce-kv (fn [mask key detail]
                 (assoc mask key (faces/resolve-material (or (get base key) inherited) detail)))
               (or base {}) (or (:faces (.. object -userData -paintDetails)) {}))))

(defn- apply-small-details! [^js object inherited colors?]
  (let [inherited (select-keys inherited [:base :metalness :roughness :glow])
        mask (projected-mask! object inherited)
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
          ;; Deindexing preserves triangle order and source-space coordinates.
          (set! (.. object -geometry -userData -paintFaceKeys) (.. old -userData -paintFaceKeys))
          (set! (.. object -geometry -userData -paintTopology) (.. old -userData -paintTopology))
          (.dispose old)))
      (let [geometry (.-geometry object)
            index (face-index! object)
            attribute (or (.getAttribute geometry "color")
                          (let [value (three/BufferAttribute. (js/Float32Array. (* 9 (triangle-count geometry))) 3)]
                            (.setAttribute geometry "color" value) value))
            finish (or (.getAttribute geometry "shipyardFinish")
                       (let [value (three/BufferAttribute. (js/Float32Array. (* 9 (triangle-count geometry))) 3)]
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
                (.setXYZ finish vertex (:metalness inherited) (:roughness inherited) (get inherited :glow 0)))))
          (doseq [key (if (and (= inherited old-inherited) (.. object -userData -paintDirtyFaces))
                        (.. object -userData -paintDirtyFaces)
                        (keys (if (= inherited old-inherited) (merge old-mask mask) mask)))
                  :when (or (not= inherited old-inherited) (not= (get old-mask key) (get mask key)))]
            (let [entry (.get index key)
                  {:keys [base metalness roughness glow] :or {glow 0}} (faces/resolve-material inherited (get mask key))
                  [r g b] (mapv material/srgb->linear base)]
              (doseq [triangle (if (number? entry) [entry] (array-seq entry))]
                (dotimes [corner 3]
                  (let [vertex (+ (* triangle 3) corner)]
                    (.setXYZ attribute vertex r g b)
                    (.setXYZ finish vertex metalness roughness glow))))))
          (set! (.-needsUpdate attribute) true)
          (set! (.-needsUpdate finish) true)
          (set! (.. object -userData -detailSignature) signature))
        (when enabled? (.setRGB (.-color surface) 1 1 1))))
    (set! (.. object -userData -paintDirtyFaces) nil)))

(defn cancel! [^js object]
  (set! (.. object -userData -paintGeneration) (inc (or (.. object -userData -paintGeneration) 0))))

(defn- triangle-material [^js object inherited region-overrides detail-overrides triangle]
  (let [regions (.. object -userData -paintRegions) details (.. object -userData -paintDetails)
        layers (.. object -userData -paintLayers)
        region-index (when-let [indices (when-not (:projection-reset? regions) (:triangle-layers regions))] (aget indices triangle))
        detail-index (when-let [indices (when-not (:projection-reset? details) (:triangle-details details))] (aget indices triangle))
        region (or (.get region-overrides triangle)
                   (if (and region-index (pos? region-index))
                     (or (get layers (nth (:layer-table regions) region-index nil)) inherited) inherited))
        base (if (and detail-index (pos? detail-index))
               (or (nth (:detail-table details) detail-index nil) region) region)
        override (.get detail-overrides triangle)]
    (cond
      (= ::erased override) region
      (some? override) (faces/resolve-material region override)
      :else base)))

(defn- changed-mask-keys [before after]
  (filter #(not= (get before %) (get after %)) (distinct (concat (keys before) (keys after)))))

(defn- apply-prepared-details! [^js object inherited colors?]
  (let [geometry (.-geometry object) surface (.-material object)
        mask (projected-mask! object inherited)
        regions (.. object -userData -paintRegions) details (.. object -userData -paintDetails)
        layers (.. object -userData -paintLayers)
        active? (boolean (or (seq mask) (and (seq layers) (:triangle-layers regions)) (:triangle-details details)))
        signature [inherited mask regions details colors? layers]
        [old-inherited old-mask old-regions old-details _ old-layers] (.. object -userData -preparedCompletedSignature)
        count (triangle-count geometry)]
    ;; Every caller reinstalls the base material. Vertex colors already contain
    ;; that base, so restore their white multiplier even when buffers are reused.
    (when (and (not colors?) (.-vertexColors surface))
      (.setRGB (.-color surface) 1 1 1))
    (when (and colors? (.-vertexColors surface))
      (set! (.-vertexColors surface) false)
      (set! (.-needsUpdate surface) true))
    (when (not= signature (.. object -userData -preparedSignature))
      (let [full? (or (.. object -userData -paintPreparing)
                      (nil? (.. object -userData -preparedCompletedSignature))
                      (not= inherited old-inherited) (not= layers old-layers)
                      (not= (select-keys regions [:triangle-layers :layer-table :projection-reset?])
                            (select-keys old-regions [:triangle-layers :layer-table :projection-reset?]))
                      (not= (select-keys details [:triangle-details :detail-table :projection-reset?])
                            (select-keys old-details [:triangle-details :detail-table :projection-reset?])))
            changed (when-not full?
                      (distinct (concat (changed-mask-keys old-mask mask)
                                        (remove (or (:erased details) #{}) (:erased old-details))
                                        (remove (or (:erased old-details) #{}) (:erased details)))))
            triangles (if full? (range count)
                          (vec (distinct (mapcat #(topology/triangles (.. geometry -userData -preparedTopology) %) changed))))]
        (cancel! object)
        (set! (.. object -userData -preparedSignature) signature)
        (let [started (js/performance.now)
              stats #js {:chunks 0 :triangles (clojure.core/count triangles) :full full? :maximumMs 0 :elapsedMs 0}
              generation (.. object -userData -paintGeneration)
              attribute (or (.getAttribute geometry "color") (three/BufferAttribute. (js/Float32Array. (* 9 count)) 3))
              finish (or (.getAttribute geometry "shipyardFinish") (three/BufferAttribute. (js/Float32Array. (* 9 count)) 3))
              palette (js/Map.)
              region-overrides (js/Map.) detail-overrides (js/Map.)
              _ (when (seq layers)
                  (doseq [[key name] (:faces regions)
                          triangle (topology/triangles (.. geometry -userData -preparedTopology) key)]
                    (.set region-overrides triangle (or (get layers name) inherited))))
              _ (doseq [[key value] (:faces details) triangle (topology/triangles (.. geometry -userData -preparedTopology) key)]
                  (.set detail-overrides triangle value))
              _ (doseq [key (:erased details) triangle (topology/triangles (.. geometry -userData -preparedTopology) key)]
                  (.set detail-overrides triangle ::erased))
              write! (fn [triangle]
                       (let [value (triangle-material object inherited region-overrides detail-overrides triangle)
                             channels (or (.get palette value)
                                          (let [channels (clj->js (concat (mapv material/srgb->linear (:base value))
                                                                          [(:metalness value) (:roughness value) (get value :glow 0)]))]
                                            (.set palette value channels) channels))]
                         (dotimes [corner 3]
                           (let [vertex (+ (* triangle 3) corner)]
                             (.setXYZ attribute vertex (aget channels 0) (aget channels 1) (aget channels 2))
                             (.setXYZ finish vertex (aget channels 3) (aget channels 4) (aget channels 5))))
                         (when-not full?
                           (.addUpdateRange attribute (* triangle 9) 9)
                           (.addUpdateRange finish (* triangle 9) 9))))]
          (set! (.. object -userData -paintPreparing) true)
          (set! (.. object -userData -paintPreparationStats) stats)
          (letfn [(step! [remaining]
                    (when (= generation (.. object -userData -paintGeneration))
                      (let [chunk-start (js/performance.now)
                            chunk (take 256 remaining)
                            remaining (drop 256 remaining)]
                        (doseq [triangle chunk] (write! triangle))
                        (set! (.-chunks stats) (inc (.-chunks stats)))
                        (set! (.-maximumMs stats) (max (.-maximumMs stats) (- (js/performance.now) chunk-start)))
                        (if (seq remaining) (js/setTimeout #(step! remaining) 0)
                            (do
                              (.setAttribute geometry "color" attribute)
                              (.setAttribute geometry "shipyardFinish" finish)
                              (when (pos? (.-triangles stats))
                                (when full?
                                  (.clearUpdateRanges attribute)
                                  (.clearUpdateRanges finish))
                                (set! (.-needsUpdate attribute) true)
                                (set! (.-needsUpdate finish) true))
                              (let [enabled? (and active? (not colors?))]
                                (when (not= enabled? (.-vertexColors surface))
                                  (set! (.-vertexColors surface) enabled?)
                                  (set! (.-needsUpdate surface) true))
                                (when-not colors?
                                  (if enabled? (.setRGB (.-color surface) 1 1 1)
                                      (let [[r g b] (mapv material/srgb->linear (:base inherited))]
                                        (.setRGB (.-color surface) r g b)))))
                              (set! (.-value (install-finish! surface)) active?)
                              (set! (.-elapsedMs stats) (- (js/performance.now) started))
                              (set! (.. object -userData -preparedCompletedSignature) signature)
                              (set! (.. object -userData -paintPreparing) false))))))]
            (step! triangles)))))))

(defn apply-details! [^js object inherited colors?]
  ;; Small authoring previews preserve immediate feedback. Detailed source meshes
  ;; use backend expansion/identity and yield bounded material upload preparation.
  (if (.. object -geometry -userData -preparedTopology)
    (apply-prepared-details! object (select-keys inherited [:base :metalness :roughness :glow]) colors?)
    (apply-small-details! object inherited colors?)))
