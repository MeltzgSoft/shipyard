(ns shipyard.regions.mirror-guide
  "A non-pickable, world-space guide for the active Regions mirror plane."
  (:require ["three" :as three]
            [shipyard.math :as math]
            [shipyard.regions.symmetry :as symmetry]))

(defn- bounds! [^js object orientation]
  (let [cached (.. object -userData -regionMirrorBounds)]
    (if (and cached (= orientation (:orientation cached))) (:bounds cached)
        (let [_ (.updateMatrixWorld object true)
              box (.setFromObject (three/Box3.) object true)
              bounds [(vec (.toArray (.-min box))) (vec (.toArray (.-max box)))]]
          (set! (.. object -userData -regionMirrorBounds) {:orientation orientation :bounds bounds})
          bounds))))

(defn- clear! [{:keys [^js scene region-mirror-guide]}]
  (when-let [{:keys [^js object]} @region-mirror-guide]
    (.remove scene object)
    (.traverse object (fn [^js child]
                        (.dispose (.-geometry child))
                        (.dispose (.-material child))))
    (reset! region-mirror-guide nil)))

(defn- create! [^js scene color]
  (let [geometry (three/PlaneGeometry. 1 1)
        plane (three/Mesh. geometry (three/MeshBasicMaterial.
                                     #js {:color color :transparent true :opacity 0.16
                                          :side three/DoubleSide :depthWrite false :toneMapped false}))
        outline (three/LineSegments. (three/EdgesGeometry. geometry)
                                     (three/LineBasicMaterial.
                                      #js {:color color :transparent true :opacity 0.65
                                           :depthWrite false :toneMapped false}))]
    (set! (.-name plane) "region-mirror-plane")
    (.add plane outline)
    (.add scene plane)
    plane))

(defn update!
  "Only the active Browse runtime calls this. The guide is separate from `parts`,
  so the brush's ID pass and mount picking never include it. Cache mesh bounds;
  changing an offset only updates the existing plane's transform."
  [{:keys [^js scene current parts region-mirror-guide] :as sys} axis-colors]
  (let [form (.getElementById js/document "region-stroke")
        field (fn [name] (when form (.namedItem (.-elements form) name)))
        {:keys [part-id mesh-key orientation]} @current
        object (get @parts part-id)
        enabled (and form object (seq (.getClientRects form))
                     (= part-id (.-value (field "part-id")))
                     (= mesh-key (.-value (field "mesh-key")))
                     (.-checked (field "mirror")))
        raw (when enabled (.-value (field "mirror-offset")))
        offset (when (seq raw) (math/parse-finite-double raw))
        axis (when enabled (keyword (.-value (field "mirror-axis"))))]
    (if (and enabled (contains? axis-colors axis)
             (not (.. (field "mirror-offset") -validity -badInput))
             (or (empty? raw) (some? offset)))
      (let [key [object orientation axis offset]]
        (when (not= key (:key @region-mirror-guide))
          (let [{:keys [position size normal]} (symmetry/plane-guide (bounds! object orientation) axis offset)
                color (get axis-colors axis)
                ^js plane (or (:object @region-mirror-guide) (create! scene color))]
            (.fromArray (.-position plane) (to-array position))
            (.set (.-scale plane) (first size) (second size) 1)
            (.setFromUnitVectors (.-quaternion plane) (three/Vector3. 0 0 1)
                                 (.fromArray (three/Vector3.) (to-array normal)))
            (.traverse plane (fn [^js child] (.setHex (.. child -material -color) color)))
            (reset! region-mirror-guide {:object plane :key key}))))
      (clear! sys))))

(defn stats [{:keys [^js scene region-mirror-guide]}]
  (when-let [{:keys [^js object]} @region-mirror-guide]
    (let [normal (.applyQuaternion (three/Vector3. 0 0 1) (.-quaternion object))]
      {:visible (and (.-visible object) (= scene (.-parent object)))
       :position (vec (.toArray (.-position object)))
       :normal (vec (.toArray normal))
       :size [(.. object -scale -x) (.. object -scale -y)]
       :color (.getHexString (.. object -material -color))
       :opacity (.. object -material -opacity)
       :depth-write (.. object -material -depthWrite)})))
