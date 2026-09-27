(ns shipyard.paint.glow
  "Selective bloom and bounded surface lighting for emissive paint."
  (:require ["three" :as three]
            ["three/examples/jsm/postprocessing/UnrealBloomPass.js" :refer [UnrealBloomPass]]
            ["three/examples/jsm/postprocessing/Pass.js" :refer [FullScreenQuad]]))

(def light-limit 8)

(defn- patches! [^js object]
  (let [geometry (.-geometry object) surface (.-material object)
        position (.getAttribute geometry "position") index (.-index geometry)
        finish (.getAttribute geometry "shipyardFinish") color (.getAttribute geometry "color")
        faces? (and (.-vertexColors surface) finish color)
        signature [geometry (when faces? (.-version finish)) (when faces? (.-version color))
                   (boolean faces?) (.-emissiveIntensity surface) (.getHex (.-emissive surface))]]
    (if (= signature (.. object -userData -glowSignature))
      (.. object -userData -glowPatches)
      (let [groups (js/Array. 6) a (three/Vector3.) b (three/Vector3.) c (three/Vector3.)
            ab (three/Vector3.) normal (three/Vector3.)
            count (/ (if index (.-count index) (.-count position)) 3)]
        (when (or faces? (pos? (.-emissiveIntensity surface)))
          (dotimes [triangle count]
            (let [offset (* triangle 3) vertex (if index (.getX index offset) offset)
                  glow (if faces? (.getZ finish vertex) (.-emissiveIntensity surface))
                  r (if faces? (.getX color vertex) (.. surface -emissive -r))
                  g (if faces? (.getY color vertex) (.. surface -emissive -g))
                  b-color (if faces? (.getZ color vertex) (.. surface -emissive -b))]
              (when (and (pos? glow) (pos? (+ r g b-color)))
                (.fromBufferAttribute a position vertex)
                (.fromBufferAttribute b position (if index (.getX index (inc offset)) (inc offset)))
                (.fromBufferAttribute c position (if index (.getX index (+ offset 2)) (+ offset 2)))
                (.crossVectors normal (.subVectors ab b a) (.subVectors normal c a))
                (let [area (* 0.5 (.length normal))]
                  (when (pos? area)
                    (.normalize normal)
                    (let [axis (if (> (abs (.-x normal)) (abs (.-y normal)))
                                 (if (> (abs (.-x normal)) (abs (.-z normal))) 0 2)
                                 (if (> (abs (.-y normal)) (abs (.-z normal))) 1 2))
                          bucket (+ (* axis 2) (if (neg? (.getComponent normal axis)) 0 1))
                          ^js group (or (aget groups bucket)
                                        (aset groups bucket #js {:area 0 :energy 0 :position (three/Vector3.)
                                                                 :normal (three/Vector3.) :color (three/Color. 0)}))
                          weight (* area glow)]
                      (.addScaledVector (.-position group) (.multiplyScalar (.add (.add a b) c) (/ 1 3)) weight)
                      (.addScaledVector (.-normal group) normal weight)
                      (set! (.. group -color -r) (+ (.. group -color -r) (* weight r)))
                      (set! (.. group -color -g) (+ (.. group -color -g) (* weight g)))
                      (set! (.. group -color -b) (+ (.. group -color -b) (* weight b-color)))
                      (set! (.-area group) (+ (.-area group) area))
                      (set! (.-energy group) (+ (.-energy group) weight)))))))))
        (let [patches (vec (keep (fn [^js group]
                                   (when group
                                     (.multiplyScalar (.-position group) (/ 1 (.-energy group)))
                                     (.normalize (.-normal group))
                                     (.multiplyScalar (.-color group) (/ 1 (.-energy group)))
                                     group)) (array-seq groups)))]
          (set! (.. object -userData -glowSignature) signature)
          (set! (.. object -userData -glowPatches) patches)
          patches)))))

(defn sources [objects]
  (->> objects
       (mapcat (fn [^js object]
                 (for [^js patch (patches! object)]
                   (let [scale (.getMaxScaleOnAxis (.-matrixWorld object))
                         radius (* scale (js/Math.sqrt (.-area patch)))
                         normal (.transformDirection (.clone (.-normal patch)) (.-matrixWorld object))
                         position (.applyMatrix4 (.clone (.-position patch)) (.-matrixWorld object))]
                     (.addScaledVector position normal (* radius 0.15))
                     {:position position :color (.-color patch) :radius radius
                      :power (* scale scale (.-energy patch))}))))
       (sort-by :power >)
       (take light-limit)
       (vec)))

(defn- emission-material! [^js object]
  (or (.. object -userData -glowMaterial)
      (let [surface (three/ShaderMaterial.
                     #js {:uniforms #js {:emission #js {:value (three/Color.)}
                                         :intensity #js {:value 0} :faces #js {:value false}}
                          :vertexShader "attribute vec3 color; attribute vec3 shipyardFinish; varying vec3 tint; varying float glow; void main(){tint=color;glow=shipyardFinish.z;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }"
                          :fragmentShader "uniform vec3 emission; uniform float intensity; uniform bool faces; varying vec3 tint; varying float glow; void main(){gl_FragColor=vec4(faces?tint*glow:emission*intensity,1.0);}"})]
        (set! (.-defaultAttributeValues surface) #js {:color #js [0 0 0] :shipyardFinish #js [0 0 0]})
        (set! (.. object -userData -glowMaterial) surface)
        surface)))

(defn dispose-object! [^js object]
  (when-let [material (.. object -userData -glowMaterial)] (.dispose material)))

(defn- create! [^js renderer ^js scene]
  (let [target (fn [] (three/WebGLRenderTarget. 1 1 #js {:type three/HalfFloatType}))
        base (target) emission (target)
        bloom (UnrealBloomPass. (three/Vector2. 1 1) 0.55 0 0)
        ^js bloom-target (aget (.-renderTargetsHorizontal bloom) 0)
        material (three/ShaderMaterial.
                  #js {:uniforms #js {:base #js {:value (.-texture base)}
                                      :bloom #js {:value (.-texture bloom-target)}}
                       :depthTest false :depthWrite false
                       :vertexShader "varying vec2 vUv; void main(){vUv=uv;gl_Position=vec4(position.xy,0.0,1.0);}"
                       :fragmentShader "uniform sampler2D base; uniform sampler2D bloom; varying vec2 vUv; void main(){gl_FragColor=vec4(texture2D(base,vUv).rgb+texture2D(bloom,vUv).rgb,1.0);\n#include <tonemapping_fragment>\n#include <colorspace_fragment>\n}"})
        lights (mapv (fn [_] (let [light (three/PointLight. 0xffffff 0 1 2)] (.add scene light) light)) (range light-limit))]
    (set! (.-samples base) (min 4 (.. renderer -capabilities -maxSamples)))
    ;; Keep halos local even when the whole hull emits; the coarsest mips would
    ;; otherwise wash the entire background in the hull's color.
    (set! (.. bloom -compositeMaterial -uniforms -bloomFactors -value) #js [1 0.45 0.1 0 0])
    {:base base :emission emission :bloom bloom :quad (FullScreenQuad. material) :lights lights :size nil}))

(defn dispose! [{:keys [glow]}]
  (when-let [{:keys [^js base ^js emission ^js bloom ^js quad lights]} (some-> glow deref)]
    (.dispose base) (.dispose emission) (.dispose bloom)
    (.dispose (.-material quad)) (.dispose quad)
    (doseq [^js light lights] (.removeFromParent light) (.dispose light))
    (reset! glow nil)))

(defn render!
  "Returns true when the glow pipeline rendered the scene; otherwise use the ordinary path."
  [{:keys [glow parts mount-colors-enabled ^js renderer ^js scene ^js camera ^js canvas]}]
  (let [objects (vec (vals @parts))
        emitters (when-not @mount-colors-enabled (sources objects))]
    (if (empty? emitters)
      (do (when @glow
            (doseq [^js light (:lights @glow)] (set! (.-visible light) false))
            (swap! glow assoc :active? false)) false)
      (let [state (or @glow (reset! glow (create! renderer scene)))
            {:keys [^js base ^js emission ^js bloom ^js quad lights]} state
            size [(max 1 (js/Math.round (* (.-clientWidth canvas) (.getPixelRatio renderer))))
                  (max 1 (js/Math.round (* (.-clientHeight canvas) (.getPixelRatio renderer))))]
            [w h] size]
        (when (not= size (:size state))
          (.setSize base w h)
          (.setSize emission (max 1 (js/Math.floor (/ w 2))) (max 1 (js/Math.floor (/ h 2))))
          (.setSize bloom (.-width emission) (.-height emission)))
        (doseq [[i ^js light] (map-indexed vector lights)]
          (set! (.-visible light) true)
          (if-let [{:keys [position color radius power]} (get emitters i)]
            (do (.copy (.-position light) position) (.copy (.-color light) color)
                (set! (.-intensity light) (* 1.5 power)) (set! (.-distance light) (* 3 radius)))
            (set! (.-intensity light) 0)))
        (.setRenderTarget renderer base) (.clear renderer true true true) (.render renderer scene camera)
        (let [background (.-background scene)
              originals (mapv (fn [^js object] [object (.-material object)]) objects)
              helpers (remove (set objects) (array-seq (.-children scene)))
              visible (mapv (fn [^js object] [object (.-visible object)]) helpers)]
          (try
            (set! (.-background scene) (three/Color. 0))
            (doseq [[^js helper _] visible] (set! (.-visible helper) false))
            (doseq [[^js object ^js surface] originals]
              (let [material (emission-material! object)]
                (.copy (.. material -uniforms -emission -value) (.-emissive surface))
                (set! (.. material -uniforms -intensity -value) (.-emissiveIntensity surface))
                (set! (.. material -uniforms -faces -value) (boolean (.-vertexColors surface)))
                (set! (.-material object) material)))
            (.setRenderTarget renderer emission) (.clear renderer true true true) (.render renderer scene camera)
            (finally
              (set! (.-background scene) background)
              (doseq [[^js object surface] originals] (set! (.-material object) surface))
              (doseq [[^js helper value] visible] (set! (.-visible helper) value)))))
        (.render bloom renderer nil emission 0 false)
        (.setRenderTarget renderer nil)
        (.render quad renderer)
        (swap! glow assoc :active? true :size size :source-count (count emitters))
        true))))

(defn stats [{:keys [glow]}]
  (when-let [state @glow]
    {:active? (:active? state) :source-count (if (:active? state) (:source-count state) 0)
     :size (:size state)}))

(defn sample-lighting!
  "Test-only sampling of the lit scene before bloom, so halo cannot fake illumination."
  [{:keys [glow ^js renderer ^js scene ^js camera ^js canvas]} x y]
  (let [ratio (.getPixelRatio renderer)
        active? (:active? @glow)
        target (if active? (:base @glow)
                   (three/WebGLRenderTarget. (js/Math.round (* ratio (.-clientWidth canvas)))
                                             (js/Math.round (* ratio (.-clientHeight canvas))) #js {:type three/HalfFloatType}))
        pixels (js/Uint16Array. 4)]
    (try
      (when-not active?
        (.setRenderTarget renderer target) (.clear renderer true true true) (.render renderer scene camera))
      (.readRenderTargetPixels renderer target (js/Math.floor (* x ratio))
                               (- (.-height target) 1 (js/Math.floor (* y ratio))) 1 1 pixels)
      (mapv #(three/DataUtils.fromHalfFloat (aget pixels %)) (range 3))
      (finally (.setRenderTarget renderer nil) (when-not active? (.dispose target))))))
