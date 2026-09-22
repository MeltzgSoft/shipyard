(ns shipyard.viewport
  "The browser island (TECHNICAL.md §7.2).

  The WebGL client module. It owns the renderer, the scene, the
  camera, `OrbitControls`, a neutral studio environment, a map of part-id ->
  `Object3D`, and the `.symesh` decoder - and it listens for `shipyard:*`
  events on `document.body` rather than being driven by swaps, because a swap
  would destroy the WebGL context and every buffer on the GPU with it
  (SPEC §6.1).

  The hot path is deliberately small and imperative. ClojureScript earns its
  place here in the decoder, the event handling and the scene bookkeeping - not
  in per-frame matrix math, where `(set! (.-x (.-position o)) 1.0)` is plainly
  worse than the JavaScript."
  (:require [shipyard.regions.brush :as region-brush]
            [shipyard.regions.model :as regions]
            ["three" :as three]
            ["three/examples/jsm/controls/OrbitControls.js" :refer [OrbitControls]]
            ["three/examples/jsm/environments/RoomEnvironment.js" :refer [RoomEnvironment]]
            [cljs.reader :as edn]
            [shipyard.assembly.scene :as assembly-scene]
            [shipyard.bulk-orientation.save-state :as bulk-saves]
            [shipyard.interface-colors :as interface-colors]
            [shipyard.math :as math]
            [shipyard.mount.split :as split]
            [shipyard.part.orientation :as orientation]
            [shipyard.paint.render :as paint-render]
            [shipyard.paint.brush :as brush]
            [shipyard.scheme.material :as paint-material]
            [shipyard.wire :as wire]))

(goog-define ^boolean TEST-HOOKS false)

(defonce ^:private state (atom nil))

;; --- geometry ---------------------------------------------------------------

(defn decode->geometry
  "A decoded `.symesh` as a `BufferGeometry`.

  The arrays are typed-array views over the received buffer (§6.4), so nothing
  is copied or parsed per vertex: they are handed to the GPU as they arrived."
  [{:keys [positions normals indices]}]
  (let [g (three/BufferGeometry.)]
    (.setAttribute g "position" (three/BufferAttribute. positions 3))
    (if normals
      (.setAttribute g "normal" (three/BufferAttribute. normals 3))
      ;; The encoder only omits normals for a mesh the welder left flat.
      (.computeVertexNormals g))
    (.setIndex g (three/BufferAttribute. indices 1))
    g))

(defn- material []
  ;; PBR from the start: M5's paint schemes depend on it, and retrofitting
  ;; lighting is worse than building on it (§7.2).
  (three/MeshStandardMaterial. #js {:color 0x9aa4af :metalness 0.05 :roughness 0.65}))

(def ^:private canonical-axes
  [{:axis :x :direction [1.0 0.0 0.0] :color 0xff5c5c :color-css "#ff5c5c"}
   {:axis :y :direction [0.0 1.0 0.0] :color 0x5ce080 :color-css "#5ce080"}
   {:axis :z :direction [0.0 0.0 1.0] :color 0x57a7ff :color-css "#57a7ff"}])

(def ^:private orientation-guide-size 156.0)
(def ^:private orientation-guide-padding 12.0)
(def ^:private orientation-guide-top 30.0)

(defn- dispose-material! [material]
  (if (array? material)
    (doseq [m material] (some-> m .dispose))
    (some-> material .dispose)))

(defn- dispose-object!
  "Release an object's GPU buffers. Removing an `Object3D` from a scene does not
  free anything - loading twenty parts in sequence without this grows GPU
  memory without bound."
  [^js obj]
  (when obj
    (.traverse obj (fn [^js child]
                     (some-> child .-geometry .dispose)
                     (dispose-material! (.-material child))))))

;; --- camera -----------------------------------------------------------------

(defn frame-bounds
  "Camera position and target that fit a bbox in a `fov`-degree frustum.

  Read from the `.symesh` header, so framing costs nothing: the alternative is
  scanning a million vertices for bounds the encoder already wrote down."
  [bbox-min bbox-max fov]
  (let [c      (mapv #(/ (+ %1 %2) 2.0) bbox-min bbox-max)
        size   (mapv - bbox-max bbox-min)
        radius (max 1e-6 (* 0.5 (apply max size)))
        ;; 1.6 is slack, so a long hull does not touch the frustum edges.
        dist   (* 1.6 (/ radius (Math/tan (/ (* fov (/ Math/PI 180.0)) 2.0))))]
    {:target c
     :position [(+ (c 0) (* dist 0.7)) (+ (c 1) (* dist 0.5)) (+ (c 2) (* dist 0.7))]
     :radius radius}))

(defn- frame! [{:keys [^js camera ^js controls workspace ^js canvas]} bbox-min bbox-max]
  (let [inspector (when (= workspace :paint) (.querySelector js/document ".paint-editor"))
        width (max 1 (- (.-clientWidth canvas) (if inspector (+ 42 (.-offsetWidth inspector)) 0)))
        aspect (/ width (max 1 (.-clientHeight canvas)))
        fov (if inspector (* 2 (/ 180 js/Math.PI) (js/Math.atan (* (min 1 aspect) (js/Math.tan (/ (* (.-fov camera) js/Math.PI) 360))))) (.-fov camera))
        {:keys [target position radius]} (frame-bounds bbox-min bbox-max fov)]
    (.set (.-position camera) (position 0) (position 1) (position 2))
    (.set (.-target controls) (target 0) (target 1) (target 2))
    (set! (.-near camera) (/ radius 100.0))
    (set! (.-far camera) (* radius 1000.0))
    (.updateProjectionMatrix camera)
    (.update controls)))

(defn- orient-object! [^js object part-orientation]
  (when object
    (let [[x y z w] (orientation/orientation-of part-orientation)]
      (.set (.-quaternion object) x y z w)
      (.updateMatrixWorld object true)))
  object)

(defn- v3 [[x y z]] (three/Vector3. x y z))

(defn- rotation-point [axis angle radius]
  (let [s (* radius (Math/sin angle))
        c (* radius (Math/cos angle))]
    (case axis
      :x [0.0 c s]
      :y [s 0.0 c]
      :z [c s 0.0])))

(defn- rotation-tangent [axis angle]
  (let [s (Math/sin angle)
        c (Math/cos angle)]
    (case axis
      :x [0.0 (- s) c]
      :y [c 0.0 (- s)]
      :z [(- s) c 0.0])))

(defn- frontmost! [^js object]
  (.traverse object
             (fn [^js child]
               (when-let [material (.-material child)]
                 (set! (.-depthTest material) false)
                 (set! (.-depthWrite material) false))))
  (set! (.-renderOrder object) 5)
  object)

(defn- rotation-arc [{:keys [axis color]}]
  (let [end-angle (* 1.62 Math/PI)
        points (into-array (map #(v3 (rotation-point axis (* end-angle (/ % 40.0)) 0.9))
                                (range 41)))
        geometry (doto (three/BufferGeometry.)
                   (.setFromPoints points))
        line (three/Line. geometry
                          (three/LineBasicMaterial. #js {:color color
                                                         :transparent true
                                                         :opacity 0.72}))
        arrow (three/ArrowHelper. (v3 (rotation-tangent axis end-angle))
                                  (v3 (rotation-point axis end-angle 0.9))
                                  0.28 color 0.18 0.09)
        group (three/Group.)]
    (.add group line)
    (.add group arrow)
    (frontmost! group)))

(defn- orientation-guide-object [part-orientation]
  (let [box (three/BoxGeometry. 1.1 0.72 1.55)
        wireframe (three/LineSegments.
                   (three/EdgesGeometry. box)
                   (three/LineBasicMaterial. #js {:color 0xd7e0e8
                                                  :transparent true
                                                  :opacity 0.82}))
        group (three/Group.)]
    (.dispose box)
    (set! (.-name wireframe) "part-orientation-wireframe")
    (frontmost! wireframe)
    (doseq [{:keys [direction color] :as axis} canonical-axes]
      (.add group (frontmost! (three/ArrowHelper. (v3 direction)
                                                  (three/Vector3.)
                                                  1.35 color 0.24 0.1)))
      (.add group (rotation-arc axis)))
    (.add group wireframe)
    (orient-object! wireframe part-orientation)
    {:object group
     :wireframe wireframe
     :orientation (orientation/orientation-of part-orientation)}))

(defn- current-part? [{:keys [current]} part-id mesh-key]
  (let [loaded @current]
    (and (= part-id (:part-id loaded))
         (= mesh-key (:mesh-key loaded)))))

;; --- scene bookkeeping ------------------------------------------------------

(defn- clear-orientation-guide! [{:keys [^js orientation-scene orientation-guide]}]
  (when-let [{:keys [^js object]} @orientation-guide]
    (.remove orientation-scene object)
    (dispose-object! object))
  (reset! orientation-guide nil))

(defn- install-orientation-guide!
  [{:keys [^js orientation-scene orientation-guide] :as sys} part-orientation]
  (clear-orientation-guide! sys)
  (let [{:keys [^js object] :as guide}
        (orientation-guide-object part-orientation)]
    (.add orientation-scene object)
    (reset! orientation-guide guide)))

(defn- update-orientation-guide! [{:keys [orientation-guide]} part-orientation]
  (when-let [{:keys [^js wireframe]} @orientation-guide]
    (let [normalized (orientation/orientation-of part-orientation)]
      (orient-object! wireframe normalized)
      (swap! orientation-guide assoc :orientation normalized))))

(defn put-part!
  "Put `obj` in the scene as `part-id`, disposing whatever was there under that
  id. The primitive: it replaces one part and leaves the rest alone, which is
  what M3 assembly will want when a slot changes."
  [{:keys [^js scene parts] :as sys} part-id obj]
  (when-let [old (get @parts part-id)]
    (.remove scene old)
    (dispose-object! old))
  (swap! parts assoc part-id obj)
  (.add scene obj)
  sys)

(defn show-only!
  "Make `obj` the only thing in the scene.

  M1 is a single-part viewer (SPEC §10) - picking a part shows that part - and
  `put-part!` alone does not give you that. Keyed by part id, it replaces the
  same part and adds a different one, so browsing the library accumulated a
  mesh per part until the tab ran out of GPU memory (#47).

  The keyed map stays, because M3 assembly holds several parts at once. This is
  the M1 policy on top of it, and it lives on the client on purpose: the
  alternative - having the server emit `shipyard:clear` alongside every
  `shipyard:load-mesh` - makes correct behaviour depend on the dispatch order
  of two htmx events, and empties the scene for a frame with nothing gained."
  [{:keys [^js scene parts] :as sys} part-id obj]
  (doseq [[id ^js old] @parts
          :when (not= id part-id)]
    (.remove scene old)
    (dispose-object! old))
  (swap! parts select-keys [part-id])
  (put-part! sys part-id obj))

(defn- clear-preview! [{:keys [^js scene preview]}]
  (when-let [{:keys [^js object]} @preview]
    (.remove scene object)
    (dispose-object! object))
  (reset! preview nil))

(defn- clear-preview-fragment! []
  (when-let [target (.getElementById js/document "facet-preview")]
    (set! (.-innerHTML target) "")))

(defn- clear-authoring-preview! [sys]
  (clear-preview! sys)
  (clear-preview-fragment!))

(defn- clear-interface-highlights! [{:keys [^js scene interfaces]}]
  (when-let [{:keys [^js object]} @interfaces]
    (.remove scene object)
    (dispose-object! object))
  (reset! interfaces nil))

(defn- clear-mount-markers! [{:keys [^js scene mount-markers]}]
  (doseq [[_ ^js marker] @mount-markers]
    (.remove scene marker)
    (dispose-object! marker))
  (reset! mount-markers {}))

(defn- put-mount-marker! [{:keys [^js scene mount-markers mount-colors-enabled]} slot payload]
  (when-let [position (:mount-position payload)]
    (when-let [old (get @mount-markers slot)]
      (.remove scene old)
      (dispose-object! old))
    (let [marker (three/Mesh.
                  (three/SphereGeometry. 0.8 12 8)
                  (three/MeshBasicMaterial. #js {:color (:color payload)
                                                 :transparent true
                                                 :opacity 0.95
                                                 :depthTest false
                                                 :depthWrite false}))]
      (.set (.-position marker) (nth position 0) (nth position 1) (nth position 2))
      (set! (.-visible marker) @mount-colors-enabled)
      (set! (.-renderOrder marker) 20)
      (set! (.-name marker) (str "mount-marker-" slot))
      (swap! mount-markers assoc slot marker)
      (.add scene marker))))

(defn- sync-mount-markers! [sys marker-data]
  (let [{:keys [mount-markers]} sys
        wanted (set (keys marker-data))]
    (doseq [[slot marker] @mount-markers :when (not (contains? wanted slot))]
      (.remove (:scene sys) marker)
      (dispose-object! marker)
      (swap! mount-markers dissoc slot))
    (doseq [[slot payload] marker-data]
      (put-mount-marker! sys slot payload))))

(declare sync-authoring-button!)

(defn clear! [{:keys [^js canvas parts authoring current repeat bulk] :as sys}]
  (when-let [assembly (:assembly sys)] (swap! assembly assembly-scene/leave))
  (when-let [generation (:browse-generation sys)] (swap! generation inc))
  (clear-authoring-preview! sys)
  (clear-interface-highlights! sys)
  (clear-mount-markers! sys)
  (clear-orientation-guide! sys)
  (reset! authoring nil)
  (reset! current nil)
  (reset! repeat nil)
  (.remove (.-classList canvas) "stage__canvas--authoring")
  (doseq [[_ ^js obj] @parts]
    (.removeFromParent obj)
    (dispose-object! obj))
  (reset! parts {})
  (when bulk (reset! bulk {}))
  (sync-authoring-button! sys))

;; --- mount authoring --------------------------------------------------------

(defn- reflect-frame [{:mount/keys [pos axis roll]}
                      {:keys [plane-keyword offset] :as mirror}]
  (let [part-orientation (:orientation mirror)]
    {:mount/pos (orientation/reflect-position part-orientation plane-keyword offset pos)
     :mount/axis (orientation/reflect-direction part-orientation plane-keyword axis)
     :mount/roll (orientation/reflect-direction part-orientation plane-keyword roll)}))

(defn- reflect-point! [^js p {:keys [plane-keyword offset] :as mirror}]
  (let [[x y z] (orientation/reflect-position
                 (:orientation mirror) plane-keyword offset [(.-x p) (.-y p) (.-z p)])]
    (.set p x y z))
  p)

(def ^:private interface-plane-epsilon 0.08)
(def ^:private interface-normal-cos 0.999)

(defn- length-sq [v]
  (math/dot v v))

(defn- triangle-count [^js obj]
  (quot (.. obj -geometry -index -count) 3))

(defn- triangle-points [^js obj triangle-index]
  (let [source (.-geometry obj)
        position (.getAttribute source "position")
        index (.-index source)]
    (mapv (fn [corner]
            (let [vertex-index (.getX index (+ (* triangle-index 3) corner))
                  p (three/Vector3.)]
              (.fromBufferAttribute p position vertex-index)
              [(.-x p) (.-y p) (.-z p)]))
          (range 3))))

(defn- triangle-normal [[a b c]]
  (math/normalize (math/cross (math/subtract b a) (math/subtract c a))))

(defn- triangle-center [points]
  (mapv (fn [idx] (/ (reduce + (map #(nth % idx) points)) 3.0))
        (range 3)))

(defn- point-on-mount-plane? [pos axis p]
  (<= (Math/abs (math/dot axis (math/subtract p pos))) interface-plane-epsilon))

(defn- interface-triangle? [pos axis points]
  (when-let [normal (triangle-normal points)]
    (and (every? #(point-on-mount-plane? pos axis %) points)
         (>= (Math/abs (math/dot normal axis)) interface-normal-cos))))

(defn- quantized [x]
  (js/Math.round (* 100000.0 x)))

(defn- point-key [[x y z]]
  (str (quantized x) ":" (quantized y) ":" (quantized z)))

(defn- edge-key [a b]
  (let [ak (point-key a)
        bk (point-key b)]
    (if (neg? (compare ak bk))
      (str ak "|" bk)
      (str bk "|" ak))))

(defn- triangle-edge-keys [[a b c]]
  [(edge-key a b) (edge-key b c) (edge-key c a)])

(defn- adjacency [triangles]
  (let [edges (reduce
               (fn [by-edge [triangle-index points]]
                 (reduce #(update %1 %2 (fnil conj #{}) triangle-index)
                         by-edge
                         (triangle-edge-keys points)))
               {}
               triangles)]
    (reduce
     (fn [adj [_ neighbours]]
       (if (> (count neighbours) 1)
         (reduce (fn [a n]
                   (update a n (fnil into #{}) (disj neighbours n)))
                 adj
                 neighbours)
         adj))
     {}
     edges)))

(defn- connected-indices [adjacency start]
  (loop [queue (list start)
         seen #{}]
    (if-let [triangle-index (first queue)]
      (if (contains? seen triangle-index)
        (recur (rest queue) seen)
        (recur (concat (rest queue) (get adjacency triangle-index))
               (conj seen triangle-index)))
      (vec (sort seen)))))

(defn- interface-facet [^js obj {:mount/keys [pos axis]}]
  (when (and pos axis)
    (let [triangles (keep (fn [triangle-index]
                            (let [points (triangle-points obj triangle-index)]
                              (when (interface-triangle? pos axis points)
                                [triangle-index points])))
                          (range (triangle-count obj)))]
      (when (seq triangles)
        (let [start (first (first (sort-by (fn [[_ points]]
                                             (length-sq
                                              (math/subtract (triangle-center points) pos)))
                                           triangles)))]
          {:indices (connected-indices (adjacency triangles) start)
           :candidates (count triangles)})))))

(defn- object-geometry-count [^js obj]
  (let [n (atom 0)]
    (when obj
      (.traverse obj (fn [^js child] (when (.-geometry child) (swap! n inc)))))
    @n))

(defn- sync-authoring-button! [{:keys [authoring-enabled current]}]
  (when-let [button (when (and (exists? js/document) (.-querySelector js/document))
                      (.querySelector js/document "[data-authoring-toggle]"))]
    (let [enabled? @authoring-enabled]
      (.setAttribute button "aria-pressed" (if enabled? "true" "false"))
      (set! (.-disabled button) (nil? @current))
      (set! (.-textContent button) (if enabled? "Done picking" "Pick mount face")))))

(defn- enter-authoring! [{:keys [^js canvas parts authoring authoring-enabled current] :as sys}
                         part-id mesh-key]
  (when (and (get @parts part-id)
             (current-part? {:current current} part-id mesh-key))
    (clear-authoring-preview! sys)
    (reset! authoring-enabled true)
    (reset! authoring {:part-id part-id :mesh-key mesh-key})
    (.add (.-classList canvas) "stage__canvas--authoring")
    (sync-authoring-button! sys)))

(defn- exit-authoring! [{:keys [^js canvas authoring authoring-enabled repeat] :as sys}]
  (clear-authoring-preview! sys)
  (reset! authoring-enabled false)
  (reset! authoring nil)
  (reset! repeat nil)
  (.remove (.-classList canvas) "stage__canvas--authoring")
  (sync-authoring-button! sys))

(defn- authoring! [sys {:keys [state part-id mesh-key]}]
  (case state
    :enter (enter-authoring! sys part-id mesh-key)
    :exit  (exit-authoring! sys)
    nil))

(defn- shipyard-event! [event payload]
  (.dispatchEvent (.-body js/document)
                  (js/CustomEvent. (str "shipyard:" event)
                                   #js {:bubbles true
                                        :detail  #js {:value (pr-str payload)}})))

(defn- authoring-toggle! [sys ^js e]
  (let [target (.-target e)
        button (when (and target (.-closest target))
                 (.closest target "[data-authoring-toggle]"))]
    (when button
      (.preventDefault e)
      (when-let [{:keys [part-id mesh-key]} @(:current sys)]
        (shipyard-event! "authoring" {:state (if @(:authoring-enabled sys) :exit :enter)
                                      :part-id part-id
                                      :mesh-key mesh-key})))))

(defn- apply-material! [^js object value colors?]
  (let [{:keys [base metalness roughness]} (or value paint-material/neutral)
        ^js surface (.-material object)
        [r g b] (mapv paint-material/srgb->linear base)]
    (set! (.. object -userData -paintMaterial) (or value paint-material/neutral))
    (if (and colors? (some? (.. object -userData -mountColor)))
      (.setHex (.-color surface) (.. object -userData -mountColor))
      (.setRGB (.-color surface) r g b))
    (set! (.-metalness surface) metalness)
    (set! (.-roughness surface) roughness)
    (paint-render/apply-details! object (or value paint-material/neutral) colors?)))

(defn- set-mount-colors! [{:keys [parts mount-markers mount-colors-enabled interfaces]} enabled?]
  (reset! mount-colors-enabled enabled?)
  (when-let [object (:object @interfaces)] (set! (.-visible object) enabled?))
  (doseq [[_ ^js object] @parts]
    (when (some? (.. object -userData -mountColor))
      (apply-material! object (.. object -userData -paintMaterial) enabled?)))
  (doseq [[_ ^js marker] @mount-markers]
    (set! (.-visible marker) enabled?)))

(defn- facet-geometry [^js obj facet-indices axis mirror]
  (let [source (.-geometry obj)
        position (.getAttribute source "position")
        index (.-index source)
        lift (v3 axis)
        values (array)]
    (doseq [triangle facet-indices
            corner (range 3)]
      (let [vertex-index (.getX index (+ (* triangle 3) corner))
            p (three/Vector3.)]
        (.fromBufferAttribute p position vertex-index)
        (when mirror (reflect-point! p mirror))
        (.addScaledVector p lift 0.002)
        (.push values (.-x p) (.-y p) (.-z p))))
    (doto (three/BufferGeometry.)
      (.setAttribute "position" (three/BufferAttribute. (js/Float32Array. values) 3)))))

(defn- preview-length [^js obj]
  (let [g (.-geometry obj)]
    (when-not (.-boundingSphere g) (.computeBoundingSphere g))
    (max 0.25 (* 0.35 (.. g -boundingSphere -radius)))))

(declare input-value checked?)

(defn- face-highlight [^js obj facet-indices frame mirror color opacity]
  (three/Mesh.
   (facet-geometry obj facet-indices (:mount/axis frame) mirror)
   (three/MeshBasicMaterial. #js {:color color
                                  :transparent true
                                  :opacity opacity
                                  :side three/DoubleSide
                                  :depthWrite false
                                  :polygonOffset true
                                  :polygonOffsetFactor -1
                                  :polygonOffsetUnits -1})))

(defn- color-int [interface-type]
  (js/parseInt (subs (interface-colors/color interface-type) 1) 16))

(defn- split-guide-object [mount color]
  (let [{:keys [frames lines]} (split/sections mount)]
    (when (seq lines)
      (let [points (into-array (mapcat #(map v3 %) lines))
            geometry (doto (three/BufferGeometry.) (.setFromPoints points))]
        {:object (three/LineSegments.
                  geometry
                  (three/LineBasicMaterial. #js {:color color :depthTest false}))
         :split-lines lines
         :split-centers (mapv :mount/pos frames)}))))

(defn- saved-facet-indices [mesh-key mount]
  (when (= mesh-key (get-in mount [:mount/facet :mesh-key]))
    (seq (get-in mount [:mount/facet :indices]))))

(defn- interface-highlight-object [^js obj mesh-key mount]
  (let [interface-type (interface-colors/type-of mount)
        color (color-int interface-type)
        ;; A newly saved mirror has no server-side triangle ids yet. Recover the
        ;; connected face from its durable frame in the live mesh so it is
        ;; coloured immediately; the next server read may cache those ids.
        facet-indices (or (saved-facet-indices mesh-key mount)
                          (some-> (interface-facet obj mount) :indices seq))
        split-guide (split-guide-object mount color)
        group (three/Group.)]
    (when facet-indices
      (.add group (face-highlight obj facet-indices mount nil color 0.42)))
    (when-let [split-object (:object split-guide)]
      (.add group split-object))
    {:type interface-type
     :mount-id (:mount/id mount)
     :triangles (count facet-indices)
     :candidates (count facet-indices)
     :split-lines (or (:split-lines split-guide) [])
     :split-centers (or (:split-centers split-guide) [])
     :object (when (or facet-indices split-guide) group)}))

(defn- interface-highlights [^js obj mesh-key mounts]
  (let [items (keep #(interface-highlight-object obj mesh-key %) mounts)
        group (three/Group.)]
    (doseq [{:keys [^js object]} items]
      (when object
        (.add group object)))
    {:object group
     :items (mapv #(dissoc % :object) (filter :object items))
     :misses (mapv #(dissoc % :object) (remove :object items))}))

(defn- preview-facet-indices [^js obj facet-indices frame]
  (or (seq facet-indices)
      (some-> (interface-facet obj frame) :indices seq)))

(defn- form-twist-degrees []
  (some-> (.querySelector js/document ".mount-wizard__form input[name=twist-deg]")
          (.-value)
          (math/parse-finite-double)))

(defn- roll-for-preview [{:mount/keys [axis roll]}]
  (let [degrees (or (form-twist-degrees) 0.0)
        rotated (doto (v3 roll)
                  (.applyAxisAngle (v3 axis) (* degrees (/ js/Math.PI 180.0))))]
    [(.-x rotated) (.-y rotated) (.-z rotated)]))

(defn- frame-up [{:mount/keys [axis roll]}]
  (math/cross axis roll))

(defn- split-preview [frame]
  (when-let [form (.querySelector js/document ".mount-wizard__form")]
    (let [capacity (math/parse-finite-double (input-value form "input[name=capacity]"))
          direction (keyword (or (input-value form "select[name=split-direction]") "vertical"))
          source-frame (some-> (input-value form "input[name=frame]") (edn/read-string))]
      (when (and capacity (> capacity 1))
        (split/sections (assoc frame :mount/capacity capacity
                               :mount/split (split/metadata-for source-frame frame direction)))))))

(defn- add-split-preview! [^js group frame length]
  (let [{:keys [frames lines]} (split-preview frame)]
    (doseq [points lines]
      (let [geometry (doto (three/BufferGeometry.)
                       (.setFromPoints (into-array (map v3 points))))]
        (.add group (three/Line. geometry (three/LineBasicMaterial. #js {:color 0xffffff :depthTest false})))))
    (doseq [section frames]
      (.add group (three/ArrowHelper. (v3 (:mount/axis section)) (v3 (:mount/pos section))
                                      (* length 0.65) 0xffffff (* length 0.14) (* length 0.05))))
    {:split-lines lines :split-centers (mapv :mount/pos frames)}))

(defn- preview-object [^js obj {:keys [facet-indices frame]} mirror]
  (let [frame (assoc frame :mount/roll (roll-for-preview frame))
        axis (:mount/axis frame)
        roll (:mount/roll frame)
        up (frame-up frame)
        pos (:mount/pos frame)
        length (preview-length obj)
        facet-indices (preview-facet-indices obj facet-indices frame)
        highlight (when facet-indices
                    (face-highlight obj facet-indices frame nil 0xf0c65a 0.56))
        axis-line (three/ArrowHelper. (v3 axis) (v3 pos) length 0xf0c65a (* length 0.22) (* length 0.08))
        roll-line (three/ArrowHelper. (v3 roll) (v3 pos) (* length 0.75) 0x69d2c0 (* length 0.16) (* length 0.06))
        up-line (three/ArrowHelper. (v3 up) (v3 pos) (* length 0.75) 0xff7a90 (* length 0.16) (* length 0.06))
        mirrored-frame (when mirror (reflect-frame frame mirror))
        group (doto (three/Group.)
                (.add axis-line)
                (.add roll-line)
                (.add up-line))]
    (when highlight
      (.add group highlight))
    (when mirrored-frame
      (when facet-indices
        (.add group (face-highlight obj facet-indices mirrored-frame mirror 0x79a9ff 0.48)))
      (.add group (three/ArrowHelper. (v3 (:mount/axis mirrored-frame))
                                      (v3 (:mount/pos mirrored-frame))
                                      length 0x79a9ff (* length 0.22) (* length 0.08)))
      (.add group (three/ArrowHelper. (v3 (:mount/roll mirrored-frame))
                                      (v3 (:mount/pos mirrored-frame))
                                      (* length 0.75) 0x8fd8ff (* length 0.16) (* length 0.06)))
      (.add group (three/ArrowHelper. (v3 (frame-up mirrored-frame))
                                      (v3 (:mount/pos mirrored-frame))
                                      (* length 0.75) 0xffa7b7 (* length 0.16) (* length 0.06))))
    (merge (add-split-preview! group frame length)
           {:object group
            :frame frame
            :mirror-frame mirrored-frame
            :facet-indices (vec facet-indices)})))

(defn- mirror-form-values [part-orientation]
  (when-let [form (.querySelector js/document ".mount-wizard__form")]
    (let [plane (input-value form "select[name=mirror-plane]")
          offset (math/parse-finite-double
                  (or (input-value form "input[name=mirror-offset]") "0"))]
      (when (and (checked? form "input[name=mirror]")
                 (= "socket" (input-value form "select[name=kind]"))
                 (contains? #{"x" "y" "z"} plane)
                 offset)
        {:plane plane
         :plane-keyword (keyword plane)
         :offset offset
         :orientation part-orientation}))))

(defn- install-preview! [{:keys [^js scene parts current authoring preview preview-revision]}
                         {:keys [part-id mesh-key] :as data}]
  (when (and (current-part? {:current current} part-id mesh-key)
             (= {:part-id part-id :mesh-key mesh-key} @authoring))
    (when-let [obj (get @parts part-id)]
      (when-let [{:keys [^js object]} @preview]
        (.remove scene object)
        (dispose-object! object))
      (let [mirror (mirror-form-values (:orientation @current))
            base-frame (or (:base-frame data) (:frame data))
            {:keys [object frame mirror-frame facet-indices split-lines split-centers]}
            (preview-object obj (assoc data :frame base-frame) mirror)
            revision (swap! preview-revision inc)]
        (orient-object! object (:orientation @current))
        (.add scene object)
        (reset! preview (assoc data
                               :base-frame base-frame
                               :frame frame
                               :split-lines split-lines
                               :split-centers split-centers
                               :facet-indices facet-indices
                               :object object
                               :revision revision
                               :mirror mirror
                               :mirror-frame mirror-frame))))))

(defn- draw-preview! [sys payload]
  (install-preview! sys (select-keys payload [:part-id :mesh-key :facet-indices :frame
                                              :roll-ambiguous? :roll-source])))

(defn- draw-interfaces! [{:keys [^js scene parts current interfaces] :as sys}
                         {:keys [part-id mesh-key mounts orientation]}]
  (when (current-part? {:current current} part-id mesh-key)
    (clear-interface-highlights! sys)
    (when-let [obj (get @parts part-id)]
      (try
        (let [{:keys [^js object items misses]} (interface-highlights obj mesh-key mounts)]
          (orient-object! object (or orientation (:orientation @current)))
          (set! (.-visible object) @(:mount-colors-enabled sys))
          (when (seq items)
            (.add scene object))
          (reset! interfaces {:object object
                              :part-id part-id
                              :mesh-key mesh-key
                              :items items
                              :misses misses}))
        (catch :default e
          (js/console.error "shipyard: interface highlights failed" e)
          (reset! interfaces {:part-id part-id
                              :mesh-key mesh-key
                              :items []
                              :error (str e)}))))))

(defn- canvas-pointer! [^js pointer ^js canvas ^js e]
  (let [rect (.getBoundingClientRect canvas)
        x (- (.-clientX e) (.-left rect))
        y (- (.-clientY e) (.-top rect))]
    (.set pointer
          (- (* 2.0 (/ x (.-width rect))) 1.0)
          (- 1.0 (* 2.0 (/ y (.-height rect)))))))

(defn- append-form-value! [^js body k v]
  (let [field (if (keyword? k) (name k) (str k))]
    (if (and (coll? v) (not (map? v)))
      (doseq [item v]
        (.append body field (if (keyword? item) (name item) (str item))))
      (.append body field (if (keyword? v) (name v) (str v))))))

(defn- form-body [values]
  (let [body (js/URLSearchParams.)]
    (doseq [[k v] values]
      (append-form-value! body k v))
    body))

(defn- dom-repeat-values []
  (some-> (.getElementById js/document "mount-authoring")
          (.getAttribute "data-repeat-values")
          (edn/read-string)))

(defn- dom-interface-values []
  (when-let [authoring (.getElementById js/document "mount-authoring")]
    (when-let [mounts (.getAttribute authoring "data-interface-mounts")]
      {:part-id (.getAttribute authoring "data-part-id")
       :mesh-key (.getAttribute authoring "data-mesh-key")
       :mounts (edn/read-string mounts)})))

(defn- trigger-header! [header]
  (when header
    (let [events (js/JSON.parse header)]
      (doseq [event (js/Object.keys events)]
        (.dispatchEvent (.-body js/document)
                        (js/CustomEvent. event
                                         #js {:bubbles true
                                              :detail  #js {:value (aget events event)}}))))))

(defn- dom-edit-values []
  (when-let [form (.querySelector js/document ".mount-wizard__form")]
    (when-let [original-mount-id (input-value form "input[name=original-mount-id]")]
      {"original-mount-id" original-mount-id
       "mount-id" (input-value form "input[name=mount-id]")
       "accepts" (input-value form "select[name=accepts]")
       "capacity" (input-value form "input[name=capacity]")
       "split-direction" (input-value form "select[name=split-direction]")
       "twist-deg" (input-value form "input[name=twist-deg]")})))

(defn- post-facet! [{:keys [authoring repeat]} triangle-index]
  (let [target (.getElementById js/document "facet-preview")
        {:keys [part-id mesh-key]} @authoring]
    (when (and target part-id mesh-key)
      (-> (js/fetch "/facet"
                    #js {:method "POST"
                         :headers #js {"Content-Type" "application/x-www-form-urlencoded"}
                         :body (form-body (merge (dissoc @repeat "kind" :kind "mount-id" :mount-id)
                                                 (dissoc (dom-repeat-values) "kind" :kind "mount-id" :mount-id)
                                                 (dissoc (dom-edit-values) "kind" :kind)
                                                 {"part-id" part-id
                                                  "mesh-key" mesh-key
                                                  "triangle-index" (str triangle-index)}))})
          (.then (fn [^js res]
                   (let [trigger (.get (.-headers res) "HX-Trigger")]
                     (-> (.text res)
                         (.then (fn [html]
                                  (set! (.-innerHTML target) html)
                                  (some-> js/window .-htmx (.process target))
                                  (trigger-header! trigger)))))))
          (.catch (fn [e]
                    (js/console.error "shipyard: facet selection failed" e)))))))

(defn- input-value [^js form selector]
  (some-> (.querySelector form selector) .-value))

(defn- checked? [^js form selector]
  (boolean (some-> (.querySelector form selector) .-checked)))

(defn- sync-socket-fields!
  "Keep socket-only controls in the DOM while a plug is selected so choosing
  socket does not discard their values, but hide and disable them until then."
  [^js form]
  (let [socket? (= "socket" (input-value form "select[name=kind]"))]
    (doseq [^js field (array-seq (.querySelectorAll form "[data-socket-only]"))]
      (set! (.-hidden field) (not socket?))
      (set! (.-disabled field) (not socket?))
      (doseq [^js control (array-seq (.querySelectorAll field "input, select"))]
        (set! (.-disabled control) (not socket?))))))

(defn- sync-socket-fields-from-dom! []
  (when-let [form (.querySelector js/document ".mount-wizard__form")]
    (sync-socket-fields! form)))

(defn- mirror-id [mount-id] (str mount-id "-mirror"))

(defn- update-mirror-id! [^js form previous-id]
  (when-let [mirror-input (.querySelector form "input[name=mirror-id]")]
    (when (= (.-value mirror-input) (mirror-id previous-id))
      (let [next-id (input-value form "input[name=mount-id]")]
        (set! (.-value mirror-input) (mirror-id next-id))
        (.setAttribute mirror-input "data-mirror-source" next-id)))))

(defn- next-mount-id [prefix used-ids]
  (loop [ordinal 1]
    (let [candidate (str prefix "-" ordinal)]
      (if (contains? used-ids candidate)
        (recur (inc ordinal))
        candidate))))

(defn- used-mount-ids [^js mount-id]
  (try
    (set (edn/read-string (or (.getAttribute mount-id "data-used-mount-ids") "[]")))
    (catch :default _ #{})))

(defn- selected-mount-prefix [^js form]
  (if (= "plug" (input-value form "select[name=kind]"))
    "plug"
    (input-value form "select[name=accepts]")))

(defn- update-mount-id-prefix! [^js form]
  (when-let [mount-id (.querySelector form "input[name=mount-id]")]
    (let [previous-prefix (.getAttribute mount-id "data-mount-prefix")
          next-prefix (selected-mount-prefix form)
          current-id (.-value mount-id)]
      (when (and previous-prefix
                 next-prefix
                 (or (= current-id previous-prefix)
                     (.startsWith current-id (str previous-prefix "-"))))
        (let [next-id (next-mount-id next-prefix (used-mount-ids mount-id))]
          (set! (.-value mount-id) next-id)
          (update-mirror-id! form current-id)))
      (when next-prefix
        (.setAttribute mount-id "data-mount-prefix" next-prefix)))))

(defn- sync-mirror-id-from-mount-id! [^js form]
  (when-let [mirror-input (.querySelector form "input[name=mirror-id]")]
    (update-mirror-id! form (.getAttribute mirror-input "data-mirror-source"))))

(defn- event-form [^js e]
  (let [target (.-target e)]
    (when (and target (.-closest target))
      (.closest target ".mount-wizard__form"))))

(defn- preview-data [preview-record]
  (-> (select-keys preview-record [:part-id :mesh-key :facet-indices
                                   :roll-ambiguous? :roll-source])
      (assoc :base-frame (:base-frame preview-record)
             :frame (:base-frame preview-record))))

(defn- refresh-preview-from-form! [sys ^js e]
  (let [target (.-target e)
        form (when (and target (.-closest target))
               (.closest target ".mount-wizard__form"))]
    (when (and form @(:preview sys))
      (install-preview! sys (preview-data @(:preview sys))))))

(defn- refresh-preview-after-swap! [sys]
  (when (and (.querySelector js/document ".mount-wizard__form")
             @(:preview sys))
    (install-preview! sys (preview-data @(:preview sys)))))

(def ^:private world-orientation-inputs
  {"part-yaw-deg" {:axis :y :attribute "data-orientation-yaw"}
   "part-pitch-deg" {:axis :x :attribute "data-orientation-pitch"}
   "part-roll-deg" {:axis :z :attribute "data-orientation-roll"}})

(defn- input-angle [^js input]
  (let [value (.-value input)]
    (if (= "" value)
      0.0
      (math/parse-finite-double value))))

(defn- set-world-orientation! [^js form orientation]
  (let [quaternion-input (.querySelector form "input[name=part-orientation-quaternion]")
        mode-input (.querySelector form "input[name=part-orientation-mode]")]
    (when quaternion-input
      (set! (.-value quaternion-input) (.join (clj->js orientation) ",")))
    (when mode-input
      (set! (.-value mode-input) "world"))))

(defn- orient-part! [{:keys [parts current interfaces preview] :as sys}
                     {:keys [part-id saved?] :as payload}]
  (when (= part-id (:part-id @current))
    (let [part-orientation (orientation/orientation-of (:orientation payload))
          saved-orientation (if saved?
                              part-orientation
                              (:saved-orientation @current))
          guide-orientation (orientation/relative-orientation saved-orientation
                                                              part-orientation)]
      (swap! current assoc
             :orientation part-orientation
             :saved-orientation saved-orientation)
      (orient-object! (get @parts part-id) part-orientation)
      (orient-object! (:object @interfaces) part-orientation)
      (orient-object! (:object @preview) part-orientation)
      (update-orientation-guide! sys guide-orientation)
      (when-let [[bbox-min bbox-max] (:bounds @current)]
        (let [[oriented-min oriented-max]
              (orientation/oriented-bounds bbox-min bbox-max part-orientation)]
          (frame! sys oriented-min oriented-max))))))

(defn- refresh-orientation-from-form! [sys ^js e]
  (let [target (.-target e)
        form (when (and target (.-closest target))
               (.closest target ".part-orientation__form"))
        {:keys [axis attribute]} (get world-orientation-inputs (.-name target))
        previous (some-> form (.getAttribute attribute) math/parse-finite-double)
        candidate (when axis (input-angle target))]
    (when (and form axis (some? previous) (some? candidate))
      (let [part-orientation (orientation/rotate-around-world-axis
                              (:orientation @(:current sys)) axis (- candidate previous))]
        (.setAttribute form attribute (str candidate))
        (set-world-orientation! form part-orientation)
        (orient-part! sys {:part-id (input-value form "input[name=part-id]")
                           :orientation part-orientation})))))

(defn- sync-interfaces-from-dom! [sys]
  (when-let [values (dom-interface-values)]
    (draw-interfaces! sys values)))

(defn- suggest-repeat-id [id]
  (if-let [[_ prefix digits] (re-matches #"^(.*?)(\d+)$" id)]
    (str prefix (inc (js/parseInt digits 10)))
    (str id "-2")))

(defn- remember-repeat-from-submit! [{:keys [repeat]} ^js e]
  (let [form (.-target e)]
    (when (some-> form .-classList (.contains "mount-wizard__form"))
      (if (checked? form "input[name=repeat]")
        (reset! repeat {:mount-id (suggest-repeat-id (input-value form "input[name=mount-id]"))
                        :kind (input-value form "select[name=kind]")
                        :accepts (input-value form "select[name=accepts]")})
        (reset! repeat nil)))))

(defn- activate-detail-tab! [tab]
  (when-let [root (.querySelector js/document ".detail")]
    (doseq [^js button (array-seq (.querySelectorAll root "[data-detail-tab]"))]
      (let [active? (= tab (.. button -dataset -detailTab))]
        (if active?
          (.add (.-classList button) "detail__tab--active")
          (.remove (.-classList button) "detail__tab--active"))
        (.setAttribute button "aria-selected" (str active?))))
    (doseq [^js panel (array-seq (.querySelectorAll root "[data-detail-panel]"))]
      (set! (.-hidden panel) (not= tab (.. panel -dataset -detailPanel))))))

(defn- pick-face! [{:keys [^js canvas ^js camera parts authoring ^js raycaster ^js pointer] :as sys} ^js e]
  (when-let [{:keys [part-id]} @authoring]
    (when-let [obj (get @parts part-id)]
      (canvas-pointer! pointer canvas e)
      (.setFromCamera raycaster pointer camera)
      (let [hits (.intersectObject raycaster obj false)]
        (when (pos? (.-length hits))
          (let [^js hit (aget hits 0)
                face-index (.-faceIndex hit)]
            (when (some? face-index)
              (activate-detail-tab! "mounts")
              (post-facet! sys face-index))))))))

(defn- load-mesh!
  "Fetch, decode, upload, and optionally reframe. Errors are reported and
  swallowed: a part that fails to load must not take the session with it."
  [{:keys [^js scene ^js canvas authoring authoring-enabled current repeat] :as sys}
   {:keys [url part-id mesh-key frame mounts] :as payload}]
  (clear! sys)
  (let [generation @(:browse-generation sys)]
    (-> (js/fetch url)
        (.then (fn [^js res]
                 (if (.-ok res)
                   (.arrayBuffer res)
                   (throw (js/Error. (str "mesh request failed: " (.-status res)))))))
        (.then (fn [buf]
                 (when (= generation @(:browse-generation sys))
                   (let [{:keys [bbox-min bbox-max] :as mesh} (wire/decode buf)
                         part-orientation (orientation/orientation-of (:orientation payload))
                         obj (three/Mesh. (decode->geometry mesh) (material))
                         [oriented-min oriented-max]
                         (orientation/oriented-bounds bbox-min bbox-max part-orientation)]
                     (set! (.-name obj) (or part-id url))
                     (set! (.. obj -userData -partId) part-id)
                     (set! (.. obj -userData -meshKey) mesh-key)
                     (paint-render/set-regions! obj (:regions payload) (regions/preview-materials (:regions payload)))
                     (apply-material! obj (get (regions/preview-materials (:regions payload)) "Primary" paint-material/neutral) @(:mount-colors-enabled sys))
                     (orient-object! obj part-orientation)
                     (clear-authoring-preview! sys)
                     (clear-interface-highlights! sys)
                     (clear-orientation-guide! sys)
                     (reset! authoring nil)
                     (reset! current {:part-id part-id
                                      :mesh-key mesh-key
                                      :orientation part-orientation
                                      :saved-orientation part-orientation
                                      :bounds [bbox-min bbox-max]})
                     (reset! repeat nil)
                     (.remove (.-classList canvas) "stage__canvas--authoring")
                     (show-only! sys part-id obj)
                     (when @authoring-enabled
                       (enter-authoring! sys part-id mesh-key))
                     (install-orientation-guide! sys orientation/identity-quaternion)
                     (draw-interfaces! sys {:part-id part-id
                                            :mesh-key mesh-key
                                            :orientation part-orientation
                                            :mounts mounts})
                     (when frame (frame! sys oriented-min oriented-max))
                     (swap! (:status sys) assoc :state :loaded :part-id part-id)
                     (sync-authoring-button! sys)
                     scene))))
        (.catch (fn [e]
                  (when (= generation @(:browse-generation sys))
                    (js/console.error "shipyard: could not load" url e)
                    (swap! (:status sys) assoc :state :failed :message (str e))))))))

(defn- frame-assembly! [{:keys [parts] :as sys}]
  (when (seq @parts)
    (let [bounds (three/Box3.)]
      (doseq [object (vals @parts)] (.expandByObject bounds object))
      (let [minimum (.-min bounds) maximum (.-max bounds)]
        (frame! sys [(.-x minimum) (.-y minimum) (.-z minimum)]
                [(.-x maximum) (.-y maximum) (.-z maximum)])))))

(defn- load-assembly-slot! [{:keys [assembly mount-colors-enabled] :as sys} slot {:keys [token payload]}]
  (-> (js/fetch (:url payload))
      (.then (fn [^js response]
               (if (.-ok response) (.arrayBuffer response)
                   (throw (js/Error. (str "Assembly mesh request failed: " (.-status response)))))))
      (.then (fn [buffer]
               (when (assembly-scene/current? @assembly slot token)
                 (let [payload (get-in @assembly [:slots slot :payload])
                       geometry (decode->geometry (wire/decode buffer))
                       surface (material)
                       object (three/Mesh. geometry surface)]
                   (try
                     (when-let [color (:color payload)]
                       (set! (.. object -userData -mountColor) color))
                     (set! (.-matrixAutoUpdate object) false)
                     (.fromArray (.-matrix object) (clj->js (:matrix payload)))
                     (set! (.. object -userData -partId) (:part-id payload))
                     (set! (.. object -userData -meshKey) (:mesh-key payload))
                     (paint-render/set-details! object (:details payload))
                     (paint-render/set-regions! object (:regions payload) (:layers payload))
                     (apply-material! object (:material payload) @mount-colors-enabled)
                     (.updateMatrixWorld object true)
                     (put-part! sys slot object)
                     (frame-assembly! sys)
                     (swap! (:status sys) assoc :state :loaded)
                     (catch :default error
                       (dispose-object! object)
                       (throw error)))))))
      (.catch (fn [error]
                (when (assembly-scene/current? @assembly slot token)
                  (swap! (:status sys) assoc :state :failed
                         :message (str "Assembly mesh failed. Reopen Assembly to retry. " error)))))))

(defn- apply-assembly! [{:keys [assembly parts ^js scene] :as sys} event]
  (let [before @assembly
        after (assembly-scene/accept-event before event)]
    (when (not= before after)
      (when (some #(= :reset (:op %)) (:commands event)) (clear! sys))
      (doseq [[slot _] @parts
              :when (not= (get-in before [:slots slot :token])
                          (get-in after [:slots slot :token]))]
        (when-let [object (get @parts slot)]
          (.remove scene object)
          (dispose-object! object)
          (swap! parts dissoc slot)))
      (doseq [[slot marker] @(:mount-markers sys)
              :when (not= (get-in before [:slots slot :token])
                          (get-in after [:slots slot :token]))]
        (.remove scene marker)
        (dispose-object! marker)
        (swap! (:mount-markers sys) dissoc slot))
      (reset! assembly after)
      (doseq [[slot ^js object] @parts]
        (paint-render/set-details! object (get-in after [:slots slot :payload :details]))
        (paint-render/set-regions! object (get-in after [:slots slot :payload :regions]) (get-in after [:slots slot :payload :layers]))
        (apply-material! object (get-in after [:slots slot :payload :material]) @(:mount-colors-enabled sys)))
      (sync-mount-markers! sys (:mount-markers event))
      (doseq [{:keys [op slot] :as command} (:commands event)
              :when (= :set op)]
        (put-mount-marker! sys slot command))
      (doseq [[slot entry] (:slots after)
              :when (not= (:token entry) (get-in before [:slots slot :token]))]
        (load-assembly-slot! sys slot entry)))))

(defn- sync-assembly-from-dom! [sys]
  (doseq [element (array-seq (.querySelectorAll js/document "#detail [data-assembly-event]"))]
    (let [event (edn/read-string (.getAttribute element "data-assembly-event"))]
      ;; Remove before applying: unrelated swaps must not replay this response.
      ;; apply-assembly! retains its sequence/token checks for stale responses.
      (.remove element)
      (when (and (or (nil? (:workspace event)) (= (:workspace sys) (:workspace event)))
                 (or (nil? (:activation event)) (= @(:activation sys) (:activation event))))
        (apply-assembly! sys event)))))

;; --- bulk orientation -------------------------------------------------------

(defn- bulk-elements []
  (array-seq (.querySelectorAll js/document "[data-bulk-part]")))

(defn- sync-bulk-dirty! [entries]
  (doseq [card (bulk-elements)]
    (if (:dirty (get entries (.getAttribute card "data-bulk-part")))
      (.setAttribute card "data-dirty" "true")
      (.removeAttribute card "data-dirty"))))

(defn- sync-bulk-card! [entry ^js card]
  (when-let [state (:state entry)]
    (.setAttribute card "data-mesh-state" (name state))
    (when-let [status (.querySelector card ".bulk-grid__card-state")]
      (set! (.-textContent status)
            (case state :loading "Loading preview…" :failed "Could not load this preview. Retry to try again." :ready "Ready")))
    (when-let [button (.querySelector card "[data-bulk-retry]")]
      (set! (.-hidden button) (not= :failed state)))))

(defn- load-bulk-mesh! [{:keys [bulk parts ^js scene active activation]} ^js element]
  (let [part-id (.getAttribute element "data-bulk-part")
        url (.getAttribute element "data-mesh-url")
        saved (edn/read-string (.getAttribute element "data-orientation"))
        token (random-uuid)
        generation @activation]
    (when (and url (not (contains? @bulk part-id)))
      (swap! bulk assoc part-id {:loading true :state :loading :token token})
      (sync-bulk-card! (get @bulk part-id) element)
      (-> (js/fetch url)
          (.then (fn [^js response]
                   (if (.-ok response) (.arrayBuffer response)
                       (throw (js/Error. (str "Bulk mesh request failed: " (.-status response)))))))
          (.then (fn [buffer]
                   (when (and @active (= generation @activation) (= token (:token (get @bulk part-id))))
                     (let [mesh (wire/decode buffer)
                           object (three/Mesh. (decode->geometry mesh) (material))
                           tile-scene (three/Scene.)
                           tile-camera (three/PerspectiveCamera. 35 1 0.1 1000)
                           saved (orientation/orientation-of saved)]
                       (.computeBoundingSphere (.-geometry object))
                       (set! (.-environment tile-scene) (.-environment scene))
                       (set! (.-name object) part-id)
                       (orient-object! object saved)
                       (.add tile-scene object)
                       (swap! parts assoc part-id object)
                       (swap! bulk assoc part-id {:object object :saved saved :orientation saved
                                                  :scene tile-scene :camera tile-camera :token token :state :ready})
                       (when-let [card (.querySelector js/document (str "[data-bulk-part=\"" (js/CSS.escape part-id) "\"]"))]
                         (sync-bulk-card! (get @bulk part-id) card))))))
          (.catch (fn [error]
                    (js/console.error "shipyard: could not load bulk mesh" part-id error)
                    (when (and @active (= generation @activation) (= token (:token (get @bulk part-id))))
                      (swap! bulk assoc part-id {:state :failed :token token})
                      (when-let [card (.querySelector js/document (str "[data-bulk-part=\"" (js/CSS.escape part-id) "\"]"))]
                        (sync-bulk-card! (get @bulk part-id) card)))))))))

(defn- retry-bulk-mesh! [{:keys [bulk] :as sys} ^js button]
  (when-let [card (.closest button "[data-bulk-part]")]
    (let [id (.getAttribute card "data-bulk-part")]
      (when (= :failed (:state (get @bulk id)))
        (swap! bulk dissoc id)
        (load-bulk-mesh! sys card)))))

(defn- sync-bulk-from-dom! [{:keys [bulk parts bulk-refresh?] :as sys}]
  (let [elements (vec (bulk-elements))
        wanted (set (map #(.getAttribute % "data-bulk-part") elements))]
    (cond
      (and (= :orient (:workspace sys)) (empty? wanted)) nil
      (seq wanted)
      (do
        (when (or (and (empty? @bulk) (seq @parts))
                  (not (every? wanted (keys @bulk))))
          (clear! sys))
        (doseq [element elements]
          (when @bulk-refresh?
            (let [id (.getAttribute element "data-bulk-part")]
              (when-let [entry (get @bulk id)]
                (when-let [object (:object entry)]
                  (let [restored (bulk-saves/restore-baseline entry (edn/read-string (.getAttribute element "data-orientation")))]
                    (orient-object! object (:orientation restored))
                    (swap! bulk assoc id restored))))))
          (load-bulk-mesh! sys element)
          (sync-bulk-card! (get @bulk (.getAttribute element "data-bulk-part")) element)
          (when (:dirty (get @bulk (.getAttribute element "data-bulk-part")))
            (.setAttribute element "data-dirty" "true")))
        (reset! bulk-refresh? false)))))

(defn- sync-bulk-save-result! [{:keys [bulk bulk-saves activation]}]
  (when-let [element (.querySelector js/document "[data-bulk-save-result]")]
    (let [response (edn/read-string (.getAttribute element "data-bulk-save-result"))
          submitted (get-in @bulk-saves [:pending (:request response)])]
      (.removeAttribute element "data-bulk-save-result")
      (swap! bulk bulk-saves/acknowledge submitted response @activation)
      (swap! bulk-saves update :pending dissoc (:request response))
      (sync-bulk-dirty! @bulk))))

(defn- sync-bulk-save-button! [{:keys [bulk]}]
  (when-let [^js button (.querySelector js/document "[data-bulk-save-button]")]
    (set! (.-disabled button) (not-any? (comp :dirty val) @bulk))))

(defn- bulk-rotate! [{:keys [bulk bulk-step] :as sys} axis direction]
  (let [degrees (* direction @bulk-step)]
    (doseq [[part-id {:keys [^js object orientation] :as entry}] @bulk
            :when object]
      (let [next-orientation (orientation/rotate-around-world-axis orientation axis degrees)]
        (orient-object! object next-orientation)
        (swap! bulk assoc part-id (assoc entry :orientation next-orientation :dirty true))))
    (sync-bulk-dirty! @bulk)
    (sync-bulk-save-button! sys)))

(def ^:private bulk-euler-index
  "The UI's canonical axes map to `[yaw pitch roll]`, not their display order."
  {:x 1 :y 0 :z 2})

(defn- bulk-set-angle! [{:keys [bulk] :as sys} axis value]
  (when-let [degrees (math/parse-finite-double value)]
    (when-let [angle-index (get bulk-euler-index axis)]
      (doseq [[part-id {:keys [^js object orientation] :as entry}] @bulk
              :when object]
        (let [angles (assoc (orientation/to-euler-degrees orientation) angle-index degrees)
              next-orientation (apply orientation/from-euler-degrees angles)]
          (orient-object! object next-orientation)
          (swap! bulk assoc part-id (assoc entry :orientation next-orientation :dirty true))))
      (sync-bulk-dirty! @bulk)
      (sync-bulk-save-button! sys))))

(defn- bulk-step! [{:keys [bulk-step]} ^js button]
  (reset! bulk-step (js/parseFloat (.getAttribute button "data-bulk-step")))
  (doseq [^js choice (array-seq (.querySelectorAll js/document "[data-bulk-step]"))]
    (.setAttribute choice "aria-pressed" (if (= choice button) "true" "false"))))

(defn- bulk-copy-first! [{:keys [bulk] :as sys}]
  (when-let [[_ {:keys [orientation]}] (first (sort-by key (filter (comp :object val) @bulk)))]
    (doseq [[part-id {:keys [^js object] :as entry}] @bulk
            :when object]
      (orient-object! object orientation)
      (swap! bulk assoc part-id (assoc entry :orientation orientation :dirty true)))
    (sync-bulk-dirty! @bulk)
    (sync-bulk-save-button! sys)))

(defn- bulk-reset! [{:keys [bulk] :as sys}]
  (doseq [[part-id {:keys [^js object saved] :as entry}] @bulk
          :when object]
    (orient-object! object saved)
    (swap! bulk assoc part-id (assoc entry :orientation saved :dirty false)))
  (sync-bulk-dirty! @bulk)
  (doseq [^js input (array-seq (.querySelectorAll js/document "[data-bulk-angle]"))]
    (set! (.-value input) ""))
  (sync-bulk-save-button! sys))

(defn- prepare-bulk-save! [{:keys [bulk bulk-saves activation]} ^js form]
  (let [request (:sequence (swap! bulk-saves update :sequence inc))
        submitted (bulk-saves/submission request @activation @bulk)]
    (swap! bulk-saves assoc-in [:pending request] submitted)
    (set! (.-value (.querySelector form "[data-bulk-orientations]")) (pr-str (:poses submitted)))
    (set! (.-value (.querySelector form "[name=request]")) (str request))))

;; --- test hook --------------------------------------------------------------

(defn- preview-stats [{:keys [preview]}]
  (when-let [{:keys [^js object revision part-id mesh-key facet-indices frame
                     mirror mirror-frame roll-ambiguous? roll-source split-lines split-centers]} @preview]
    (clj->js {:revision revision
              :part-id part-id
              :mesh-key mesh-key
              :facet-indices facet-indices
              :triangles (count facet-indices)
              :position (:mount/pos frame)
              :split-lines split-lines
              :split-centers split-centers
              :axis (:mount/axis frame)
              :roll (:mount/roll frame)
              :up (frame-up frame)
              :mirror-visible? (boolean mirror-frame)
              :mirror-plane (:plane mirror)
              :mirror-offset (:offset mirror)
              :mirror-position (:mount/pos mirror-frame)
              :mirror-axis (:mount/axis mirror-frame)
              :mirror-roll (:mount/roll mirror-frame)
              :mirror-up (some-> mirror-frame (frame-up))
              :roll-ambiguous? roll-ambiguous?
              :roll-source (some-> roll-source name)
              :geometries (object-geometry-count object)})))

(defn- interface-stats [{:keys [interfaces]}]
  (when-let [{:keys [part-id mesh-key items misses error]} @interfaces]
    (let [item-stats (fn [{:keys [type mount-id triangles candidates split-lines split-centers]}]
                       {:type (name type)
                        :mount-id (name mount-id)
                        :triangles triangles
                        :candidates candidates
                        :split-lines split-lines
                        :split-centers split-centers})]
      (clj->js {:part-id part-id
                :mesh-key mesh-key
                :visible (boolean (some-> @interfaces :object .-visible))
                :count (count items)
                :misses (count misses)
                :error error
                :items (mapv item-stats items)
                :miss-items (mapv item-stats misses)}))))

(defn- object-orientation [^js object]
  (when object
    (let [q (.-quaternion object)]
      [(.-x q) (.-y q) (.-z q) (.-w q)])))

(defn- orientation-guide-stats
  [{:keys [^js camera ^js orientation-camera orientation-guide]}]
  (when-let [{:keys [orientation]} @orientation-guide]
    (clj->js {:wireframe? true
              :orientation orientation
              :location :top-right
              :camera-orientation (object-orientation orientation-camera)
              :viewer-camera-orientation (object-orientation camera)
              :positive-rotation-arcs (mapv :axis canonical-axes)
              :axes (mapv #(select-keys % [:axis :direction :color-css]) canonical-axes)})))

(defn- bulk-stats [{:keys [bulk]}]
  {:count (count (filter (comp :object val) @bulk))
   :dirty (count (filter (comp :dirty val) @bulk))
   :orientations (into {} (keep (fn [[part-id {:keys [object orientation]}]]
                                  (when object [part-id orientation]))) @bulk)})

(defn stats
  "Scene facts for the E2E suite (§10.3).

  Asserting on WebGL through pixels is brittle - driver, antialiasing and
  timing all move it - so the tests read this instead. Compiled out of release
  builds by `TEST-HOOKS`, so it cannot ship."
  [{:keys [^js renderer ^js camera ^js controls parts status authoring] :as sys}]
  (let [objs (vals @parts)]
    #js {:workspace (name (:workspace sys))
         :activation @(:activation sys)
         :parts     (clj->js (vec (keys @parts)))
         :vertices  (reduce + 0 (map (fn [^js o] (.. o -geometry -attributes -position -count)) objs))
         :triangles (reduce + 0 (map (fn [^js o] (paint-render/triangle-count (.-geometry o))) objs))
         :draws     (.. renderer -info -render -calls)
         :render-frame (.. renderer -info -render -frame)
         :target    (let [t (.-target controls)] #js [(.-x t) (.-y t) (.-z t)])
         :camera    (let [p (.-position camera)] #js [(.-x p) (.-y p) (.-z p)])
         :region-faces (clj->js (when-let [^js object (first objs)]
                                  (paint-render/projected-faces object camera (:canvas sys))))
         :materials (clj->js (mapv (fn [^js o] (.getHexString (.. o -material -color))) objs))
         ;; three's own count of geometries live on the GPU, decremented by
         ;; `geometry.dispose()`. The only thing here that is not derived from
         ;; `parts`, and therefore the only one that can tell "removed from the
         ;; scene" from "actually released" - which is what #47's test claimed
         ;; to check and could not.
         :geometries (.. renderer -info -memory -geometries)
         :status    (clj->js (:state @status))
         :authoring (clj->js @authoring)
         :mount-colors-enabled @(:mount-colors-enabled sys)
         :visible-mount-markers (count (filter (fn [[_ ^js marker]] (.-visible marker))
                                               @(:mount-markers sys)))
         :assembly (clj->js
                    {:mode (:mode @(:assembly sys))
                     :slots (when (= :assembly (:mode @(:assembly sys)))
                              (mapv (fn [[slot ^js object]]
                                      {:slot slot :part-id (.. object -userData -partId)
                                       :color (.getHexString (.. object -material -color))
                                       :metalness (.. object -material -metalness)
                                       :roughness (.. object -material -roughness)
                                       :details (:faces (.. object -userData -paintDetails))
                                       :vertex-colors (.. object -material -vertexColors)
                                       :finish-compiled (true? (.. object -material -userData -finishCompiled))
                                       :finish-enabled (boolean (when-let [^js uniform (.. object -material -userData -finishEnabled)] (.-value uniform)))
                                       :face-finishes (when-let [finish (.getAttribute (.-geometry object) "shipyardFinish")]
                                                        (when (<= (paint-render/triangle-count (.-geometry object)) 64)
                                                          (mapv (fn [triangle]
                                                                  {:key (paint-render/face-key (.-geometry object) triangle)
                                                                   :base (when-let [color (.getAttribute (.-geometry object) "color")]
                                                                           [(.getX color (* 3 triangle)) (.getY color (* 3 triangle)) (.getZ color (* 3 triangle))])
                                                                   :metalness (.getX finish (* 3 triangle))
                                                                   :roughness (.getY finish (* 3 triangle))})
                                                                (range (paint-render/triangle-count (.-geometry object))))))
                                       :face-centers (paint-render/projected-faces object camera (:canvas sys))
                                       :uuid (.-uuid object) :matrix (vec (.. object -matrix -elements))})
                                    @parts))})
         :bulk (clj->js (bulk-stats sys))
         :orientation (clj->js (some-> objs first object-orientation))
         :orientation-guide (orientation-guide-stats sys)
         :repeat    (clj->js @(:repeat sys))
         :interfaces (interface-stats sys)
         :preview   (preview-stats sys)}))

;; --- lifecycle --------------------------------------------------------------

(defn- resize! [{:keys [^js renderer ^js camera ^js canvas workspace]}]
  (let [w (max 1 (.-clientWidth canvas))
        h (max 1 (.-clientHeight canvas))]
    (set! (.-aspect camera) (/ w h))
    (.updateProjectionMatrix camera)
    (.setSize renderer w h false)
    (if-let [inspector (when (= workspace :paint) (.querySelector js/document ".paint-editor"))]
      (.setViewOffset camera w h (/ (+ 28 (.-offsetWidth inspector)) 2) 0 w h)
      (.clearViewOffset camera))))

(defn- sync-orientation-camera! [^js camera ^js orientation-camera]
  (let [position (three/Vector3. 0.0 0.0 6.0)]
    (.applyQuaternion position (.-quaternion camera))
    (.copy (.-position orientation-camera) position)
    (.copy (.-quaternion orientation-camera) (.-quaternion camera))
    (.updateMatrixWorld orientation-camera true)))

(defn- render-bulk!
  "Draw each scene into its DOM preview rectangle. The viewport uses the full
  tile size, while the scissor intersects the scroll container and canvas, so
  scrolling clips the model without changing its camera or framing."
  [{:keys [bulk ^js renderer ^js canvas]}]
  (when-let [^js cards (.querySelector js/document ".bulk-grid__cards")]
    (let [canvas-rect (.getBoundingClientRect canvas)
          clip (.getBoundingClientRect cards)]
      (.setScissorTest renderer true)
      (doseq [^js card (bulk-elements)
              :let [{:keys [^js object ^js scene ^js camera]}
                    (get @bulk (.getAttribute card "data-bulk-part"))]
              :when object]
        (let [^js preview (.querySelector card "[data-bulk-preview]")
              rect (.getBoundingClientRect preview)
              width (.-width rect)
              height (.-height rect)
              left (max (.-left rect) (.-left clip) (.-left canvas-rect))
              right (min (.-right rect) (.-right clip) (.-right canvas-rect))
              top (max (.-top rect) (.-top clip) (.-top canvas-rect))
              bottom (min (.-bottom rect) (.-bottom clip) (.-bottom canvas-rect))]
          (when (and (pos? width) (pos? height) (< left right) (< top bottom))
            (let [sphere (.. object -geometry -boundingSphere)
                  center (.applyQuaternion (.clone (.-center sphere)) (.-quaternion object))
                  radius (max 0.001 (.-radius sphere))
                  half-fov (/ (* (.-fov camera) Math/PI) 360.0)
                  aspect (/ width height)
                  limiting-angle (Math/atan (* (Math/tan half-fov) (min 1.0 aspect)))
                  distance (/ (* radius 1.12) (Math/sin limiting-angle))
                  position (.-position camera)]
              (set! (.-aspect camera) aspect)
              (set! (.-near camera) (max 0.0001 (- distance (* radius 1.5))))
              (set! (.-far camera) (+ distance (* radius 2)))
              (.set position 3.0 2.6 4.0)
              (.normalize position)
              (.multiplyScalar position distance)
              (.add position center)
              (.lookAt camera center)
              (.updateProjectionMatrix camera)
              (.setViewport renderer (- (.-left rect) (.-left canvas-rect))
                            (- (.-bottom canvas-rect) (.-bottom rect)) width height)
              (.setScissor renderer (- left (.-left canvas-rect))
                           (- (.-bottom canvas-rect) bottom) (- right left) (- bottom top))
              (.clearDepth renderer)
              (.render renderer scene camera)))))
      (.setScissorTest renderer false))))

(defn- render-frame!
  [{:keys [^js renderer ^js scene ^js camera ^js controls ^js canvas
           ^js orientation-scene ^js orientation-camera orientation-guide bulk] :as sys}]
  (let [w (max 1 (.-clientWidth canvas))
        h (max 1 (.-clientHeight canvas))]
    (when (.-enabled controls) (.update controls))
    (.setScissorTest renderer false)
    (.setViewport renderer 0 0 w h)
    (.clear renderer true true true)
    (if (seq @bulk)
      (render-bulk! sys)
      (.render renderer scene camera))
    (when @orientation-guide
      (let [size (min orientation-guide-size
                      (max 72.0 (* 0.38 (min w h))))
            ;; The guide is a lower-left viewport overlay, just above the
            ;; canonical axis legend. WebGL coordinates are bottom-left.
            x orientation-guide-padding
            y (+ orientation-guide-top 28.0)]
        (sync-orientation-camera! camera orientation-camera)
        (.clearDepth renderer)
        (.setViewport renderer x y size size)
        (.setScissor renderer x y size size)
        (.setScissorTest renderer true)
        (.render renderer orientation-scene orientation-camera)
        (.setScissorTest renderer false)
        (.setViewport renderer 0 0 w h)))))

(defn- environment!
  "A neutral studio IBL, generated rather than fetched: a `MeshStandardMaterial`
  with no environment renders as a flat silhouette, and shipping an HDR file
  would put a megabyte of asset in the jar for it.

  `RoomEnvironment` *is* a `Scene` in three 0.185 - the older `(.-scene env)`
  spelling hands `PMREMGenerator` an undefined and it throws inside
  `_sceneToCubeUV`."
  [^js renderer ^js scene]
  (let [pmrem (three/PMREMGenerator. renderer)
        env   (.fromScene pmrem (RoomEnvironment.) 0.04)]
    (set! (.-environment scene) (.-texture env))
    (.dispose pmrem)))

(defn- listen-event! [body sys event handler & [capture?]]
  (.addEventListener body event
                     (fn [e] (when @(:active sys) (handler e)))
                     (boolean capture?)))

(defn- preview-paint! [sys ^js event]
  (when (and (= :paint (:workspace sys)) (some-> (.-target event) (.hasAttribute "data-paint-input")))
    (when-let [^js form (.closest (.-target event) "#paint-material")]
      (let [value (fn [name] (.-value (.namedItem (.-elements form) name)))
            hex (value "base")
            material {:base (mapv #(/ (js/parseInt (subs hex % (+ % 2)) 16) 255) [1 3 5])
                      :metalness (js/parseFloat (value "metalness"))
                      :roughness (js/parseFloat (value "roughness"))}
            paths (edn/read-string (.getAttribute form "data-paint-slots"))
            layer (.getAttribute form "data-paint-layer")
            override? (= "true" (.getAttribute form "data-paint-override"))]
        (doseq [path paths]
          (if layer
            (do (swap! (:assembly sys) assoc-in [:slots path :payload :layers layer] material)
                (when (= layer "Primary") (swap! (:assembly sys) assoc-in [:slots path :payload :material] material)))
            (do (swap! (:assembly sys) assoc-in [:slots path :payload :material] material)
                (when override? (swap! (:assembly sys) assoc-in [:slots path :payload :layers] nil))))
          (when-let [object (get @(:parts sys) path)]
            (let [payload (get-in @(:assembly sys) [:slots path :payload])]
              (paint-render/set-regions! object (:regions payload) (:layers payload))
              (apply-material! object (:material payload) @(:mount-colors-enabled sys)))))))))

(defn- listen! [sys]
  (let [body (.-body js/document)
        payload (fn [^js e] (edn/read-string (.. e -detail -value)))]
    (listen-event! body sys "shipyard:load-mesh" #(load-mesh! sys (payload %)))
    (listen-event! body sys "shipyard:part-regions"
                   (fn [e]
                     (let [{:keys [part-id mesh-key regions]} (payload e)]
                       (doseq [[_ ^js object] @(:parts sys)
                               :when (and (= part-id (.. object -userData -partId)) (= mesh-key (.. object -userData -meshKey)))]
                         (paint-render/set-regions! object regions (regions/preview-materials regions))
                         (apply-material! object (get (regions/preview-materials regions) "Primary") @(:mount-colors-enabled sys))))))
    (listen-event! body sys "shipyard:clear" (fn [_] (clear! sys)))
    (listen-event! body sys "shipyard:status" #(reset! (:status sys) (payload %)))
    (listen-event! body sys "shipyard:authoring" #(authoring! sys (payload %)))
    (listen-event! body sys "shipyard:clear-preview" (fn [_] (clear-authoring-preview! sys)))
    (listen-event! body sys "shipyard:assembly" #(apply-assembly! sys (payload %)))
    (listen-event! body sys "shipyard:facet-preview" #(draw-preview! sys (payload %)))
    (listen-event! body sys "shipyard:facet-error" (fn [_] (clear-authoring-preview! sys)))
    (listen-event! body sys "shipyard:mount-repeat" #(reset! (:repeat sys) (payload %)))
    (listen-event! body sys "shipyard:interfaces" #(draw-interfaces! sys (payload %)))
    (listen-event! body sys "shipyard:part-orientation" #(orient-part! sys (payload %)))
    (listen-event! body sys "htmx:afterSwap" (fn [_]
                                               (sync-assembly-from-dom! sys)
                                               (sync-bulk-from-dom! sys)
                                               (sync-bulk-save-result! sys)
                                               (sync-bulk-save-button! sys)
                                               (doseq [button (array-seq (.querySelectorAll js/document "[data-bulk-step]"))]
                                                 (.setAttribute button "aria-pressed" (str (= @(:bulk-step sys) (js/parseFloat (.getAttribute button "data-bulk-step"))))))
                                               (sync-authoring-button! sys)
                                               (sync-socket-fields-from-dom!)
                                               (sync-interfaces-from-dom! sys)
                                               (refresh-preview-after-swap! sys)))
    (listen-event! body sys "input" (fn [e]
                                      (preview-paint! sys e)
                                      (when-let [form (event-form e)]
                                        (when (= "mount-id" (.-name (.-target e)))
                                          (sync-mirror-id-from-mount-id! form)))
                                      (refresh-preview-from-form! sys e)
                                      (refresh-orientation-from-form! sys e)))
    (listen-event! body sys "change" (fn [e]
                                       (when-let [form (event-form e)]
                                         (when (= "accepts" (.-name (.-target e)))
                                           (update-mount-id-prefix! form))
                                         (when (= "kind" (.-name (.-target e)))
                                           (sync-socket-fields! form)
                                           (update-mount-id-prefix! form)))
                                       (refresh-preview-from-form! sys e)
                                       (refresh-orientation-from-form! sys e)
                                       (when-let [^js input (some-> (.-target e) (.closest "[data-bulk-angle]"))]
                                         (bulk-set-angle! sys (keyword (.getAttribute input "data-axis")) (.-value input)))))
    ;; Capture before HTMX's bubbling listener serializes the form. Updating the
    ;; hidden field from a later submit listener leaves the current request with
    ;; its original `{}` value.
    (listen-event! body sys "submit" (fn [event]
                                       (remember-repeat-from-submit! sys event)
                                       (when-let [^js form (.-target event)]
                                         (when (.hasAttribute form "data-bulk-save")
                                           (prepare-bulk-save! sys form))))
                   true)
    (listen-event! body sys "click" (fn [e]
                                      (authoring-toggle! sys e)
                                      (let [^js target (.-target e)]
                                        (when-let [^js button (some-> target (.closest "[data-bulk-rotate]"))]
                                          (bulk-rotate! sys (keyword (.getAttribute button "data-axis"))
                                                        (js/parseFloat (.getAttribute button "data-direction"))))
                                        (when-let [^js button (some-> target (.closest "[data-bulk-step]"))]
                                          (bulk-step! sys button))
                                        (when-let [button (some-> target (.closest "[data-bulk-retry]"))]
                                          (retry-bulk-mesh! sys button))
                                        (when (some-> target (.closest "[data-bulk-copy]")) (bulk-copy-first! sys))
                                        (when (some-> target (.closest "[data-bulk-reset]")) (bulk-reset! sys)))))))

(defn- renderer!
  "nil rather than a throw when this browser cannot give us a WebGL context -
  browsing and loadouts have to keep working without a viewport (§8).

  Only the context acquisition is guarded. Wrapping the whole of `start!` in
  this catch hid a real bug behind the degraded-mode message for an afternoon:
  a scene that failed to build looked exactly like a machine with no GPU."
  [^js canvas]
  (try
    (three/WebGLRenderer. #js {:canvas canvas :antialias true})
    (catch :default e
      (js/console.warn "shipyard: no WebGL context; the viewport is disabled" e)
      nil)))

(defn- runtime! [canvas renderer mode environment]
  (let [scene    (three/Scene.)
        camera   (three/PerspectiveCamera. 45 1 0.1 1000)
        orientation-scene (three/Scene.)
        orientation-camera (three/OrthographicCamera. -2.0 2.0 2.0 -2.0 0.1 20.0)
        controls (OrbitControls. camera canvas)
        sys      {:workspace mode :active (atom (= mode :browse)) :activation (atom 0)
                  :canvas canvas :renderer renderer :scene scene :camera camera
                  :orientation-scene orientation-scene
                  :orientation-camera orientation-camera
                  :controls controls :parts (atom {}) :status (atom {:state :idle})
                  :current (atom nil) :authoring (atom nil) :authoring-enabled (atom false)
                  :preview (atom nil)
                  :assembly (atom assembly-scene/empty-state) :browse-generation (atom 0)
                  :interfaces (atom nil) :orientation-guide (atom nil)
                  :mount-markers (atom {}) :mount-colors-enabled (atom true)
                  :bulk (atom {}) :bulk-refresh? (atom false) :bulk-saves (atom {:sequence 0 :pending {}}) :bulk-step (atom 90.0)
                  :repeat (atom nil)
                  :preview-revision (atom 0)
                  :raycaster (three/Raycaster.) :pointer (three/Vector2.)}]
    (set! (.-background scene) (three/Color. 0x14171c))
    (.set (.-position orientation-camera) 3.0 2.6 4.0)
    (.lookAt orientation-camera 0.0 0.0 0.0)
    ;; Paint must stop orbiting exactly where the next brush stroke begins.
    (set! (.-enableDamping controls) (not= mode :paint))
    (set! (.-enabled controls) (= mode :browse))
    (if environment (set! (.-environment scene) environment) (environment! renderer scene))
    (listen! sys)
    (when (= mode :paint) (brush/listen! sys apply-material!))
    (when (= mode :browse) (region-brush/install! sys apply-material!))
    sys))

(defn- active-runtime [{:keys [runtimes active-workspace]}]
  (get runtimes @active-workspace))

(defn- activate-runtime! [{:keys [runtimes active-workspace] :as app} ^js detail]
  (let [destination (keyword (.-mode detail))
        previous (active-runtime app)
        next (get runtimes destination)]
    (when next
      (when (not= previous next)
        (reset! (:active previous) false)
        (set! (.-enabled (:controls previous)) false))
      (swap! (:bulk-saves previous) assoc :pending {})
      (swap! (:browse-generation previous) inc)
      (swap! (:assembly previous) assembly-scene/leave)
      (swap! (:bulk previous) #(into {} (remove (comp :loading val)) %))
      (reset! active-workspace destination)
      (reset! (:bulk-refresh? next) true)
      (reset! (:active next) true)
      (reset! (:activation next) (.-activation detail))
      (set! (.-enabled (:controls next)) true)
      (set-mount-colors! next (.-colors detail))
      (sync-authoring-button! next)
      (resize! next))))

(defn start!
  "One renderer with independently owned workspace scenes and logical editing sessions."
  [canvas]
  (when-let [renderer (renderer! canvas)]
    (let [browse (runtime! canvas renderer :browse nil)
          environment (.-environment ^js (:scene browse))
          runtimes (into {:browse browse} (map (fn [mode] [mode (runtime! canvas renderer mode environment)]))
                         [:orient :assembly :ships :paint])
          app {:runtimes runtimes :active-workspace (atom :browse)}]
      (set! (.-outputColorSpace renderer) three/SRGBColorSpace)
      (.setPixelRatio renderer (min 2 (.-devicePixelRatio js/window)))
      (set! (.-autoClear renderer) false)
      (.setClearColor renderer 0x14171c)
      (when-let [context (.getElementById js/document "workspace-context")]
        (activate-runtime! app #js {:mode (.. context -dataset -workspace)
                                    :activation (js/Number (.. context -dataset -activation))
                                    :colors (= "true" (.. context -dataset -mountColors))}))
      (resize! (active-runtime app))
      (.observe (js/ResizeObserver. #(resize! (active-runtime app))) canvas)
      (.addEventListener canvas "click" #(pick-face! (active-runtime app) %))
      (.addEventListener (.-body js/document) "shipyard:workspace"
                         #(activate-runtime! app (clj->js (edn/read-string (.. % -detail -value)))))
      (.addEventListener (.-body js/document) "shipyard:display"
                         (fn [^js event] (let [detail (edn/read-string (.. event -detail -value))] (set-mount-colors! (active-runtime app) (:colors detail)))))
      (.setAnimationLoop renderer #(render-frame! (active-runtime app)))
      app)))

(defn ^:export init []
  (when-let [canvas (.getElementById js/document "viewport")]
    (let [sys (start! canvas)]
      (reset! state sys)
      ;; Nested rather than `(and TEST-HOOKS sys)`, and the nesting is load
      ;; bearing. `and` over a non-boolean compiles to `cljs.core/truth_(...)`,
      ;; which Closure cannot fold to a constant - the hook then survives
      ;; :advanced as `truth_(false) ? window.__shipyard = ... : null` and ships.
      ;; A bare `^boolean` define emits a plain `if` that constant-folds away.
      (when TEST-HOOKS
        (when sys
          (set! (.-__shipyard js/window)
                #js {:stats (fn [] (stats (active-runtime sys)))}))))))
