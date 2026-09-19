(ns shipyard.paint.brush
  "Transient visible-surface brush. A depth-tested ID pass selects frontmost faces;
  HTMX alone commits strokes and owns navigation/durable state."
  (:require ["three" :as three]
            [cljs.reader :as edn]
            [shipyard.paint.faces :as faces]
            [shipyard.paint.render :as render]))

(defn- field [^js form name] (.namedItem (.-elements form) name))
(defn- value [form name] (.-value (field form name)))
(defn- status! [message]
  (when-let [element (.getElementById js/document "brush-status")]
    (set! (.-textContent element) message)))

(defn- id-geometry [^js source start]
  (let [geometry (if (.-index source) (.toNonIndexed source) (.clone source))
        count (render/triangle-count geometry)
        rgb (js/Float32Array. (* count 9))]
    (dotimes [triangle count]
      (let [id (+ start triangle) r (/ (bit-and id 255) 255)
            g (/ (bit-and (bit-shift-right id 8) 255) 255)
            b (/ (bit-and (bit-shift-right id 16) 255) 255)]
        (dotimes [corner 3]
          (let [offset (+ (* triangle 9) (* corner 3))]
            (aset rgb offset r) (aset rgb (inc offset) g) (aset rgb (+ offset 2) b)))))
    (.setAttribute geometry "faceId" (three/BufferAttribute. rgb 3))
    geometry))

(defn visible-buffer
  "One ID/depth render per stroke; all instances occlude, only the target paints.
  Temporary GPU resources are disposed even when readback fails."
  [{:keys [^js renderer ^js camera ^js canvas parts]} target]
  (let [width (max 1 (.-clientWidth canvas)) height (max 1 (.-clientHeight canvas))
        scene (three/Scene.) surface (three/ShaderMaterial.
                                      #js {:vertexShader "attribute vec3 faceId; varying vec3 id; void main(){ id=faceId; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }"
                                           :fragmentShader "varying vec3 id; void main(){ gl_FragColor=vec4(id,1.0); }"
                                           :toneMapped false :blending three/NoBlending})
        target-buffer (three/WebGLRenderTarget. width height #js {:minFilter three/NearestFilter :magFilter three/NearestFilter})
        old-target (.getRenderTarget renderer) old-color (.getClearColor renderer (three/Color.))
        old-alpha (.getClearAlpha renderer) old-scissor (.getScissorTest renderer)
        objects (atom []) selected (atom nil) pixels (js/Uint8Array. (* width height 4))]
    (try
      (loop [entries (seq @parts) start 1]
        (when-let [[slot ^js object] (first entries)]
          (let [end (+ start (render/triangle-count (.-geometry object)))]
            (when (> end 16777216) (throw (js/Error. "Model exceeds the brush face-ID capacity.")))
            (let [geometry (id-geometry (.-geometry object) start) mesh (three/Mesh. geometry surface)]
              (swap! objects conj mesh)
              (set! (.-matrixAutoUpdate mesh) false)
              (.copy (.-matrix mesh) (.-matrixWorld object))
              (.add scene mesh)
              (when (= slot target) (reset! selected {:start start :end end :object object})))
            (recur (next entries) end))))
      (.setRenderTarget renderer target-buffer)
      (.setScissorTest renderer false)
      (.setClearColor renderer 0 1)
      (.clear renderer)
      (.render renderer scene camera)
      (.readRenderTargetPixels renderer target-buffer 0 0 width height pixels)
      (merge @selected {:pixels pixels :width width :height height})
      (finally
        (.setRenderTarget renderer old-target)
        (.setScissorTest renderer old-scissor)
        (.setClearColor renderer old-color old-alpha)
        (doseq [^js mesh @objects] (.dispose (.-geometry mesh)))
        (.dispose surface)
        (.dispose target-buffer)))))

(defn visible-triangles [{:keys [pixels width height start end]} x y radius]
  (if-not start #{}
          (let [result (volatile! (transient #{}))]
            (doseq [py (range (max 0 (int (- y radius))) (min height (inc (int (+ y radius)))))
                    px (range (max 0 (int (- x radius))) (min width (inc (int (+ x radius)))))
                    :let [dx (- (+ px 0.5) x) dy (- (+ py 0.5) y)]
                    :when (<= (+ (* dx dx) (* dy dy)) (* radius radius))]
              (let [offset (* 4 (+ px (* (- height py 1) width)))
                    id (+ (aget pixels offset) (* 256 (aget pixels (inc offset))) (* 65536 (aget pixels (+ offset 2))))]
                (when (<= start id (dec end)) (vswap! result conj! (- id start)))))
            (persistent! @result))))

(defn- rgb [hex] (mapv #(/ (js/parseInt (subs hex % (+ % 2)) 16) 255) [1 3 5]))

(defn listen! [{:keys [^js canvas ^js controls active mount-colors-enabled] :as sys} apply-material!]
  (let [stroke (atom nil)
        cursor (.createElement js/document "div")
        form! #(.getElementById js/document "paint-brush")
        available? (fn [] (when-let [form (form!)]
                            (and @active (.-checked (field form "enabled"))
                                 (not (.-disabled (field form "enabled"))) (not @mount-colors-enabled))))
        point (fn [^js e] (let [bounds (.getBoundingClientRect canvas)] [(- (.-clientX e) (.-left bounds)) (- (.-clientY e) (.-top bounds))]))
        restore! (fn [] (when-let [{:keys [^js object before]} @stroke]
                          (render/set-details! object before)
                          (apply-material! object (.. object -userData -paintMaterial) @mount-colors-enabled)))
        sample! (fn [^js e]
                  (when-let [{:keys [buffer ^js object keys before color erase? radius last-point] :as current} @stroke]
                    (let [[x y :as now] (point e) [lx ly] (or last-point now)
                          steps (max 1 (js/Math.ceil (/ (js/Math.hypot (- x lx) (- y ly)) (max 1 (/ radius 2)))))
                          triangles (reduce into #{} (for [step (range 1 (inc steps))]
                                                       (visible-triangles buffer (+ lx (* (/ step steps) (- x lx)))
                                                                          (+ ly (* (/ step steps) (- y ly))) radius)))
                          selected (into keys (map #(render/face-key (.-geometry object) %) triangles))]
                      (if (> (count selected) faces/max-stroke-faces)
                        (do (restore!) (reset! stroke nil) (set! (.-enabled controls) true)
                            (status! "Stroke too large (maximum 1,024 faces). Use a smaller brush or shorter stroke; nothing saved."))
                        (let [result (faces/stroke before (.. object -userData -partId) (.. object -userData -meshKey) (vec selected) color erase?)]
                          (reset! stroke (assoc current :keys selected :last-point now))
                          (when (:layer result)
                            (render/set-details! object (:layer result))
                            (apply-material! object (.. object -userData -paintMaterial) false)))))))
        finish! (fn [^js e]
                  (when-let [{:keys [keys form ^js object erase? hex color]} @stroke]
                    (.preventDefault e) (.stopImmediatePropagation e)
                    (set! (.-enabled controls) @active)
                    (reset! stroke nil)
                    (if (seq keys)
                      (do (set! (.-value (field form "faces")) (pr-str (vec keys)))
                          (set! (.-value (field form "mesh-key")) (.. object -userData -meshKey))
                          (set! (.-value (field form "color")) hex)
                          (set! (.-value (field form "metalness")) (:metalness color))
                          (set! (.-value (field form "roughness")) (:roughness color))
                          (set! (.-value (field form "operation")) (if erase? "erase" "paint"))
                          (.requestSubmit form))
                      (status! "No visible faces of the selected instance under the brush."))))]
    (set! (.-className cursor) "paint-brush-cursor")
    (.appendChild (.-body js/document) cursor)
    (.addEventListener canvas "pointerdown"
                       (fn [^js e]
                         (when (and (available?) (= 0 (.-button e)) (not (.-altKey e)))
                           (.preventDefault e) (.stopImmediatePropagation e)
                           (try
                             (let [form (form!) path (edn/read-string (value form "target"))
                                   buffer (visible-buffer sys path) ^js object (:object buffer)
                                   before (when object (.. object -userData -paintDetails))]
                               (if (or (nil? object) (= "true" (.getAttribute form "data-stale-details")))
                                 (status! "Select a loaded instance. Clear incompatible details before painting a changed source.")
                                 (do (set! (.-enabled controls) false)
                                     (.setPointerCapture canvas (.-pointerId e))
                                     (reset! stroke {:buffer buffer :object object :before before :form form :keys #{}
                                                     :color {:base (rgb (value form "brush-color"))
                                                             :metalness (js/parseFloat (value form "brush-metalness"))
                                                             :roughness (js/parseFloat (value form "brush-roughness"))}
                                                     :hex (value form "brush-color")
                                                     :erase? (= "erase" (value form "mode"))
                                                     :radius (js/parseFloat (value form "radius"))})
                                     (sample! e))))
                             (catch :default _ (set! (.-enabled controls) @active)
                                    (status! "The visible-face buffer could not be prepared. Nothing saved; try again."))))) true)
    (.addEventListener canvas "pointermove"
                       (fn [^js e]
                         (let [enabled? (available?) radius (when enabled? (value (form!) "radius"))]
                           (set! (.. cursor -style -display) (if enabled? "block" "none"))
                           (when enabled?
                             (set! (.. cursor -style -width) (str (* 2 radius) "px"))
                             (set! (.. cursor -style -height) (str (* 2 radius) "px"))
                             (set! (.. cursor -style -left) (str (.-clientX e) "px"))
                             (set! (.. cursor -style -top) (str (.-clientY e) "px"))))
                         (when @stroke (.preventDefault e) (.stopImmediatePropagation e) (sample! e))) true)
    (.addEventListener canvas "pointerup" finish! true)
    (.addEventListener canvas "pointercancel" (fn [_] (restore!) (reset! stroke nil) (set! (.-enabled controls) @active)) true)
    (.addEventListener canvas "pointerleave" (fn [_] (set! (.. cursor -style -display) "none")))
    (.addEventListener canvas "wheel" (fn [^js e] (when @stroke (.preventDefault e) (.stopImmediatePropagation e))) #js {:capture true :passive false})
    (.addEventListener js/document "htmx:beforeRequest"
                       (fn [_] (when @stroke (restore!) (reset! stroke nil) (set! (.-enabled controls) @active))
                         (set! (.. cursor -style -display) "none")))))
