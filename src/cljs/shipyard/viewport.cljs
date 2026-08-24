(ns shipyard.viewport
  "The browser island (TECHNICAL.md §7.2).

  The only client-side code we write. It owns the renderer, the scene, the
  camera, `OrbitControls`, a neutral studio environment, a map of part-id ->
  `Object3D`, and the `.symesh` decoder - and it listens for `shipyard:*`
  events on `document.body` rather than being driven by swaps, because a swap
  would destroy the WebGL context and every buffer on the GPU with it
  (SPEC §6.1).

  The hot path is deliberately small and imperative. ClojureScript earns its
  place here in the decoder, the event handling and the scene bookkeeping - not
  in per-frame matrix math, where `(set! (.-x (.-position o)) 1.0)` is plainly
  worse than the JavaScript."
  (:require ["three" :as three]
            ["three/examples/jsm/controls/OrbitControls.js" :refer [OrbitControls]]
            ["three/examples/jsm/environments/RoomEnvironment.js" :refer [RoomEnvironment]]
            [cljs.reader :as edn]
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

(defn- dispose!
  "Release a part's GPU buffers. Removing an `Object3D` from a scene does not
  free anything - loading twenty parts in sequence without this grows GPU
  memory without bound."
  [^js obj]
  (some-> obj .-geometry .dispose)
  (some-> obj .-material .dispose))

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

(defn- frame! [{:keys [^js camera ^js controls]} bbox-min bbox-max]
  (let [{:keys [target position radius]} (frame-bounds bbox-min bbox-max (.-fov camera))]
    (.set (.-position camera) (position 0) (position 1) (position 2))
    (.set (.-target controls) (target 0) (target 1) (target 2))
    (set! (.-near camera) (/ radius 100.0))
    (set! (.-far camera) (* radius 1000.0))
    (.updateProjectionMatrix camera)
    (.update controls)))

;; --- scene bookkeeping ------------------------------------------------------

(defn- put-part!
  "Put `obj` in the scene as `part-id`, disposing whatever was there under that
  id. The primitive: it replaces one part and leaves the rest alone, which is
  what M3 assembly will want when a slot changes."
  [{:keys [^js scene parts] :as sys} part-id obj]
  (when-let [old (get @parts part-id)]
    (.remove scene old)
    (dispose! old))
  (swap! parts assoc part-id obj)
  (.add scene obj)
  sys)

(defn- show-only!
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
    (dispose! old))
  (swap! parts select-keys [part-id])
  (put-part! sys part-id obj))

(defn clear! [{:keys [^js scene parts]}]
  (doseq [[_ ^js obj] @parts]
    (.remove scene obj)
    (dispose! obj))
  (reset! parts {}))

(defn- load-mesh!
  "Fetch, decode, upload, and optionally reframe. Errors are reported and
  swallowed: a part that fails to load must not take the session with it."
  [{:keys [^js scene] :as sys} {:keys [url part-id frame]}]
  (-> (js/fetch url)
      (.then (fn [^js res]
               (if (.-ok res)
                 (.arrayBuffer res)
                 (throw (js/Error. (str "mesh request failed: " (.-status res)))))))
      (.then (fn [buf]
               (let [{:keys [bbox-min bbox-max] :as mesh} (wire/decode buf)
                     obj (three/Mesh. (decode->geometry mesh) (material))]
                 (set! (.-name obj) (or part-id url))
                 (show-only! sys part-id obj)
                 (when frame (frame! sys bbox-min bbox-max))
                 (swap! (:status sys) assoc :state :loaded :part-id part-id)
                 scene)))
      (.catch (fn [e]
                (js/console.error "shipyard: could not load" url e)
                (swap! (:status sys) assoc :state :failed :message (str e))))))

;; --- test hook --------------------------------------------------------------

(defn stats
  "Scene facts for the E2E suite (§10.3).

  Asserting on WebGL through pixels is brittle - driver, antialiasing and
  timing all move it - so the tests read this instead. Compiled out of release
  builds by `TEST-HOOKS`, so it cannot ship."
  [{:keys [^js renderer ^js controls parts status]}]
  (let [objs (vals @parts)]
    #js {:parts     (clj->js (vec (keys @parts)))
         :vertices  (reduce + 0 (map (fn [^js o] (.. o -geometry -attributes -position -count)) objs))
         :triangles (reduce + 0 (map (fn [^js o] (/ (.. o -geometry -index -count) 3)) objs))
         :draws     (.. renderer -info -render -calls)
         :target    (let [t (.-target controls)] #js [(.-x t) (.-y t) (.-z t)])
         :materials (clj->js (mapv (fn [^js o] (.getHexString (.. o -material -color))) objs))
         ;; three's own count of geometries live on the GPU, decremented by
         ;; `geometry.dispose()`. The only thing here that is not derived from
         ;; `parts`, and therefore the only one that can tell "removed from the
         ;; scene" from "actually released" - which is what #47's test claimed
         ;; to check and could not.
         :geometries (.. renderer -info -memory -geometries)
         :status    (clj->js (:state @status))}))

;; --- lifecycle --------------------------------------------------------------

(defn- resize! [{:keys [^js renderer ^js camera ^js canvas]}]
  (let [w (max 1 (.-clientWidth canvas))
        h (max 1 (.-clientHeight canvas))]
    (set! (.-aspect camera) (/ w h))
    (.updateProjectionMatrix camera)
    (.setSize renderer w h false)))

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

(defn- listen! [sys]
  (let [body (.-body js/document)
        payload (fn [^js e] (edn/read-string (.. e -detail -value)))]
    (.addEventListener body "shipyard:load-mesh" #(load-mesh! sys (payload %)))
    (.addEventListener body "shipyard:clear" (fn [_] (clear! sys)))
    (.addEventListener body "shipyard:status" #(reset! (:status sys) (payload %)))))

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

(defn start!
  "Build the scene against `canvas`, or return nil in degraded mode."
  [^js canvas]
  (when-let [renderer (renderer! canvas)]
    (let [scene    (three/Scene.)
          camera   (three/PerspectiveCamera. 45 1 0.1 1000)
          controls (OrbitControls. camera canvas)
          sys      {:canvas canvas :renderer renderer :scene scene :camera camera
                    :controls controls :parts (atom {}) :status (atom {:state :idle})}]
      (set! (.-outputColorSpace renderer) three/SRGBColorSpace)
      (.setPixelRatio renderer (min 2 (.-devicePixelRatio js/window)))
      (set! (.-background scene) (three/Color. 0x14171c))
      (set! (.-enableDamping controls) true)
      (environment! renderer scene)
      (resize! sys)
      (.observe (js/ResizeObserver. #(resize! sys)) canvas)
      (.setAnimationLoop renderer (fn [] (.update controls) (.render renderer scene camera)))
      (listen! sys)
      sys)))

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
                #js {:stats (fn [] (stats sys))}))))))
