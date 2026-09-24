(ns shipyard.paint.brush
  "Transient visible-surface brush. A depth-tested ID pass selects frontmost faces;
  Ordered fetches buffer face deltas; HTMX confirms the final atomic commit."
  (:require ["three" :as three]
            [cljs.reader :as edn]
            [shipyard.http.forms :as forms]
            [shipyard.paint.faces :as faces]
            [shipyard.paint.render :as render]))

(defn- field [^js form name] (.namedItem (.-elements form) name))
(defn- value [form name] (.-value (field form name)))
(defn- status! [message]
  (when-let [element (.getElementById js/document "brush-status")]
    (set! (.-textContent element) message))
  (when-let [element (.getElementById js/document "paint-header-status")]
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
  "One ID/depth render per stroke; ranges retain every instance for visible-only sampling.
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
        objects (atom []) selected (atom nil) ranges (atom {}) pixels (js/Uint8Array. (* width height 4))]
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
              (swap! ranges assoc slot {:start start :end end :object object})
              (when (= slot target) (reset! selected {:start start :end end :object object})))
            (recur (next entries) end))))
      (.setRenderTarget renderer target-buffer)
      (.setScissorTest renderer false)
      (.setClearColor renderer 0 1)
      (.clear renderer)
      (.render renderer scene camera)
      (.readRenderTargetPixels renderer target-buffer 0 0 width height pixels)
      (merge @selected {:pixels pixels :width width :height height :ranges @ranges})
      (finally
        (.setRenderTarget renderer old-target)
        (.setScissorTest renderer old-scissor)
        (.setClearColor renderer old-color old-alpha)
        (doseq [^js mesh @objects] (.dispose (.-geometry mesh)))
        (.dispose surface)
        (.dispose target-buffer)))))

(defn cached-visible-buffer!
  "Reuse one CPU picking buffer while source meshes, transforms and view are unchanged.
  Painting may deindex geometry but preserves source triangle order."
  [cache {:keys [^js camera ^js canvas parts] :as sys} target]
  (.updateMatrixWorld camera true)
  (let [signature [target (.-clientWidth canvas) (.-clientHeight canvas)
                   ;; Copy Three.js's mutable arrays; vec can retain their backing storage.
                   (mapv identity (.. camera -matrixWorld -elements))
                   (mapv identity (.. camera -projectionMatrix -elements))
                   (mapv (fn [[slot ^js object]]
                           (.updateWorldMatrix object true false)
                           [slot object (.. object -userData -meshKey)
                            (render/triangle-count (.-geometry object))
                            (mapv identity (.. object -matrixWorld -elements))]) @parts)]]
    (if (= signature (:signature @cache)) (:buffer @cache)
        (let [buffer (visible-buffer sys target)]
          (reset! cache {:signature signature :buffer buffer})
          buffer))))

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

(defn sampled-instances [buffer x y radius target cross?]
  (if-not cross?
    (if-let [entry (get-in buffer [:ranges target])]
      (let [triangles (visible-triangles (merge buffer entry) x y radius)]
        (if (seq triangles) {target triangles} {})) {})
    (let [ranges (vec (sort-by (comp :start val) (:ranges buffer)))
          ids (visible-triangles (assoc buffer :start 1 :end 16777216) x y radius)]
      (reduce (fn [result encoded]
                (let [id (inc encoded)
                      entry (loop [lo 0 hi (dec (count ranges))]
                              (when (<= lo hi)
                                (let [mid (quot (+ lo hi) 2) [_ {:keys [start end]}] (nth ranges mid)]
                                  (cond (< id start) (recur lo (dec mid))
                                        (>= id end) (recur (inc mid) hi)
                                        :else (nth ranges mid)))))]
                  (if entry (update result (first entry) (fnil conj #{}) (- id (:start (second entry)))) result)))
              {} ids))))

(defn- entries [current pending]
  (mapv (fn [[path keys]]
          {:target (pr-str path) :mesh-key (.. ^js (get-in current [:buffer :ranges path :object]) -userData -meshKey)
           :faces (vec keys)}) (filter (comp seq val) pending)))

(defn- summary! [current]
  (let [face-count (reduce + 0 (map count (vals (:keys current))))]
    (status! (str (if (:dragging? current) "Painting — " "Saving — ") face-count " faces"))
    (when-let [element (.getElementById js/document "paint-stroke-count")]
      (set! (.-textContent element) (str face-count " faces")))
    (when-let [element (.getElementById js/document "paint-stroke-list")]
      (.replaceChildren element)
      (doseq [path (distinct (concat (keys (:keys current)) (:behind current)))]
        (let [row (.createElement js/document "div") label (.createElement js/document "span")
              amount (.createElement js/document "span") behind? (not (seq (get-in current [:keys path])))]
          (set! (.-className row) (str "paint-stroke-row" (when behind? " is-behind")))
          (set! (.-textContent label) (or (get-in current [:labels (pr-str path)]) (pr-str path)))
          (set! (.-textContent amount) (if behind? "behind" (str (count (get-in current [:keys path])))))
          (.append row label amount)
          (.append element row))))))

(def lock-selector
  "#workspace-navigation button, #paint-select select, #paint-target button, #paint-create button, #paint-rename button, #mount-colors-toggle, .paint-tools button, .paint-editor input:not([type=hidden]), .paint-editor select, .paint-editor button, .paint-rail button")

(defn listen! [{:keys [^js canvas ^js controls ^js camera active mount-colors-enabled] :as sys} apply-material!]
  (let [stroke (atom nil) last-stroke (atom nil) locked (atom [])
        cursor (.createElement js/document "div")
        form! #(.getElementById js/document "paint-brush")
        available? (fn [] (when-let [form (form!)]
                            (and @active (= "true" (value form "enabled"))
                                 (not (.-disabled (field form "radius")))
                                 (not @stroke) (not @mount-colors-enabled))))
        point (fn [^js e] (let [bounds (.getBoundingClientRect canvas)] [(- (.-clientX e) (.-left bounds)) (- (.-clientY e) (.-top bounds))]))]
    (letfn [(lock! []
              (reset! locked (mapv (fn [element] (let [before (.-disabled element)]
                                                   (set! (.-disabled element) true) [element before]))
                                   (array-seq (.querySelectorAll js/document lock-selector)))))
            (unlock! []
              (doseq [[element disabled] @locked :when (.-isConnected element)] (set! (.-disabled element) disabled))
              (reset! locked []))
            (restore! [current]
              (doseq [[path before] (:before current)]
                (when-let [^js object (get-in current [:buffer :ranges path :object])]
                  (render/set-details! object before)
                  (apply-material! object (.. object -userData -paintMaterial) @mount-colors-enabled))))
            (cancel! [message retry?]
              (when-let [current @stroke]
                (js/clearInterval (:timer current))
                (when-not (:submitting? current)
                  (-> (:chain current)
                      (.then (fn [] (send-part! current (assoc (parameters current {} false (:part current)) "operation" "cancel"))))
                      (.catch (fn [_] nil))))
                (restore! current)
                (reset! last-stroke (when retry? current))
                (reset! stroke nil)
                (set! (.-enabled controls) @active)
                (unlock!)
                (status! message)
                (when retry?
                  (when-let [element (.getElementById js/document "brush-status")]
                    (let [alert (.createElement js/document "span")]
                      (.setAttribute alert "role" "alert")
                      (set! (.-textContent alert) message)
                      (.replaceChildren element alert))))))
            (live? [current]
              (and @active (identical? (:form current) (form!)) (= (:id current) (:id @stroke))))
            (set-field! [form name v] (set! (.-value (field form name)) v))
            (parameters [current pending final? part]
              {"id" (:scheme current) "target" (:target-key current)
               "stroke-id" (str (:id current)) "part" (str part) "final" (str final?)
               "entries" (pr-str (entries current pending))
               "color" (:hex current) "metalness" (str (get-in current [:color :metalness]))
               "roughness" (str (get-in current [:color :roughness])) "operation" (if (:erase? current) "erase" "paint")})
            (send-part! [current params]
              (let [form (:form current) n (inc (js/Number (value form "sequence")))
                    body (js/URLSearchParams.) headers (js/Object.assign #js {"Content-Type" "application/x-www-form-urlencoded"} (:headers current))]
                (set-field! form "sequence" n)
                (doseq [[k v] (assoc params "sequence" (str n))] (.append body k v))
                (-> (js/fetch "/paint/stroke" #js {:method "POST" :headers headers :body (.toString body)})
                    (.then (fn [^js response]
                             (when-not (and (.-ok response) (= "buffered" (.get (.-headers response) "X-Shipyard-Brush")))
                               (throw (js/Error. "Stroke part was not accepted."))))))))
            (flush! [final?]
              (when-let [current @stroke]
                (when (or final? (some seq (vals (:pending current))))
                  (let [params (parameters current (:pending current) final? (:part current))
                        task (.then (:chain current)
                                    (fn [] (when (live? current)
                                             (if final?
                                               (do (doseq [[k v] params] (set-field! (:form current) k v))
                                                   (swap! stroke assoc :submitting? true)
                                                   (forms/post! (:form current)))
                                               (send-part! current params)))))]
                    (swap! stroke assoc :pending {} :part (inc (:part current))
                           :chain (.catch task (fn [_]
                                                 (when (live? current)
                                                   (cancel! "Save not confirmed. Retry last stroke, or reopen Paint to restore saved details." true)))))))))
            (behind! [current x y]
              (let [ray (three/Raycaster.) position (three/Vector2. (- (* 2 (/ x (:width (:buffer current)))) 1)
                                                                    (- 1 (* 2 (/ y (:height (:buffer current))))))
                    objects (mapv :object (vals (:ranges (:buffer current))))]
                (.setFromCamera ray position camera)
                (let [hits (array-seq (.intersectObjects ray (to-array objects) false))
                      front (some-> (first hits) .-object)
                      behind (set (map #(.-object %) (rest hits)))]
                  (set (for [[path {:keys [object]}] (get-in current [:buffer :ranges])
                             :when (and (not (identical? object front)) (contains? behind object))] path)))))
            (sample! [^js e]
              (when-let [{:keys [buffer radius last-point target cross?] :as current} @stroke]
                (when (:dragging? current)
                  (let [[x y :as now] (point e) [lx ly] (or last-point now)
                        steps (max 1 (js/Math.ceil (/ (js/Math.hypot (- x lx) (- y ly)) (max 1 (/ radius 2)))))
                        sampled (reduce #(merge-with into %1 %2) {}
                                        (for [step (range 1 (inc steps))]
                                          (sampled-instances buffer (+ lx (* (/ step steps) (- x lx)))
                                                             (+ ly (* (/ step steps) (- y ly))) radius target cross?)))
                        deltas (into {} (for [[path triangles] sampled
                                              :let [^js object (get-in buffer [:ranges path :object])
                                                    known (get-in current [:keys path] #{})
                                                    added (into #{} (comp (map #(render/face-key (.-geometry object) %)) (remove known)) triangles)]
                                              :when (seq added)] [path added]))]
                    (if (some (:stale current) (keys deltas))
                      (cancel! "Source mesh changed. Clear instance details before repainting. Nothing saved." false)
                      (do
                        (doseq [[path added] deltas]
                          (let [^js object (get-in buffer [:ranges path :object]) before (.. object -userData -paintDetails)
                                result (faces/stroke before (.. object -userData -partId) (.. object -userData -meshKey)
                                                     (vec added) (:color current) (:erase? current))]
                            (swap! stroke update :before #(if (contains? % path) % (assoc % path before)))
                            (render/set-details! object (:layer result))
                            (set! (.. object -userData -paintDirtyFaces) added)
                            (apply-material! object (.. object -userData -paintMaterial) false)))
                        (swap! stroke #(-> % (assoc :last-point now)
                                           (update :keys (partial merge-with into) deltas)
                                           (update :pending (partial merge-with into) deltas)
                                           (update :behind into (behind! current x y))))
                        (summary! @stroke)))))))
            (finish! [^js e]
              (when (:dragging? @stroke)
                (.preventDefault e) (.stopImmediatePropagation e)
                (js/clearInterval (:timer @stroke))
                (swap! stroke assoc :dragging? false)
                (set! (.-enabled controls) @active)
                (if (some seq (vals (:keys @stroke)))
                  (do (summary! @stroke) (flush! true))
                  (cancel! "No visible faces under the brush." false))))]
      (set! (.-className cursor) "paint-brush-cursor")
      (.appendChild (.-body js/document) cursor)
      (.addEventListener canvas "pointerdown"
                         (fn [^js e]
                           (when (and (available?) (#{0 2} (.-button e)) (not (.-altKey e)))
                             (.preventDefault e) (.stopImmediatePropagation e)
                             (try
                               (let [form (form!) target-key (value form "target")
                                     target (when (= "[" (subs target-key 0 1)) (edn/read-string target-key))
                                     cross? (.-checked (field form "cross-instances"))
                                     buffer (visible-buffer sys target)]
                                 (if (and (not cross?) (not (contains? (:ranges buffer) target)))
                                   (status! "Select an individual instance, or turn on Cross instances.")
                                   (do
                                     (set! (.-enabled controls) false)
                                     (.setPointerCapture canvas (.-pointerId e))
                                     (reset! stroke {:id (random-uuid) :part 0 :chain (js/Promise.resolve) :pending {} :keys {} :before {} :behind #{}
                                                     :buffer buffer :form form :target target :target-key target-key :cross? cross? :dragging? true
                                                     :scheme (value form "id") :headers (js/JSON.parse (.. (.getElementById js/document "workspace-context") -dataset -headers))
                                                     :labels (edn/read-string (.getAttribute form "data-instance-labels"))
                                                     :stale (set (edn/read-string (.getAttribute form "data-stale-targets")))
                                                     :color {:base (rgb (value form "brush-color"))
                                                             :metalness (js/parseFloat (value form "brush-metalness"))
                                                             :roughness (js/parseFloat (value form "brush-roughness"))} :hex (value form "brush-color")
                                                     :erase? (or (= 2 (.-button e)) (= "erase" (value form "mode"))) :radius (js/parseFloat (value form "radius"))})
                                     (lock!)
                                     (swap! stroke assoc :timer (js/setInterval #(flush! false) (js/Number (.getAttribute form "data-flush-interval"))))
                                     (sample! e))))
                               (catch :default _
                                 (if @stroke (cancel! "The visible-face buffer could not be prepared. Nothing saved; try again." false)
                                     (status! "The visible-face buffer could not be prepared. Nothing saved; try again.")))))) true)
      (.addEventListener canvas "pointermove"
                         (fn [^js e]
                           (let [enabled? (or (available?) (:dragging? @stroke)) radius (when enabled? (value (form!) "radius"))]
                             (set! (.. cursor -style -display) (if enabled? "block" "none"))
                             (when enabled?
                               (set! (.. cursor -style -width) (str (* 2 radius) "px"))
                               (set! (.. cursor -style -height) (str (* 2 radius) "px"))
                               (set! (.. cursor -style -left) (str (.-clientX e) "px"))
                               (set! (.. cursor -style -top) (str (.-clientY e) "px"))))
                           (when (:dragging? @stroke) (.preventDefault e) (.stopImmediatePropagation e) (sample! e))) true)
      (.addEventListener canvas "pointerup" finish! true)
      (.addEventListener canvas "contextmenu" (fn [^js e] (when (or @stroke (available?)) (.preventDefault e) (.stopImmediatePropagation e))) true)
      (.addEventListener canvas "pointercancel" (fn [_] (cancel! "Stroke canceled. Nothing saved." false)) true)
      (.addEventListener canvas "pointerleave" (fn [_] (set! (.. cursor -style -display) "none")))
      (.addEventListener canvas "wheel" (fn [^js e] (when (:dragging? @stroke) (.preventDefault e) (.stopImmediatePropagation e))) #js {:capture true :passive false})
      (.addEventListener js/document "click"
                         (fn [^js e]
                           (when (and @active (some-> (.-target e) (.closest "#paint-brush [data-brush-retry]")))
                             (.preventDefault e) (.stopImmediatePropagation e)
                             (if-let [previous (when (identical? (:form @last-stroke) (form!)) @last-stroke)]
                               (do (reset! stroke (assoc previous :dragging? false :pending (:keys previous) :part 0 :chain (js/Promise.resolve)))
                                   (lock!) (flush! true))
                               (status! "No stroke to retry.")))) true)
      (.addEventListener js/document "htmx:beforeRequest"
                         (fn [^js e]
                           (when (and @stroke (not (identical? (.. e -detail -elt) (:form @stroke))))
                             (cancel! "Stroke canceled. Nothing saved." false))
                           (when (not (identical? (.. e -detail -elt) (:form @last-stroke)))
                             (reset! last-stroke nil))
                           (set! (.. cursor -style -display) "none")))
      (.addEventListener js/document "htmx:afterRequest"
                         (fn [^js e]
                           (when (and @stroke (:submitting? @stroke) (identical? (.. e -detail -elt) (:form @stroke)))
                             (if (or (not (.. e -detail -successful)) (.querySelector js/document "#brush-status [data-brush-result=failed]"))
                               (cancel! "Save not confirmed. Retry last stroke, or reopen Paint to restore saved details." true)
                               (do (reset! last-stroke @stroke) (reset! stroke nil) (unlock!)
                                   (status! "Details saved.")))))))))
