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
            [shipyard.interface-colors :as interface-colors]
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
    (dispose-object! old))
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

(defn clear! [{:keys [^js scene ^js canvas parts authoring current repeat] :as sys}]
  (clear-authoring-preview! sys)
  (clear-interface-highlights! sys)
  (reset! authoring nil)
  (reset! current nil)
  (reset! repeat nil)
  (.remove (.-classList canvas) "stage__canvas--authoring")
  (doseq [[_ ^js obj] @parts]
    (.remove scene obj)
    (dispose-object! obj))
  (reset! parts {}))

;; --- mount authoring --------------------------------------------------------

(defn- v3 [[x y z]] (three/Vector3. x y z))

(def ^:private mirror-plane-index {"x" 0 "y" 1 "z" 2})

(defn- parse-finite-double [s]
  (let [n (js/Number s)]
    (when (js/Number.isFinite n) n)))

(defn- reflect-coordinate [x offset]
  (- (* 2.0 offset) x))

(defn- reflect-pos [idx offset v]
  (assoc v idx (reflect-coordinate (v idx) offset)))

(defn- reflect-dir [idx v]
  (update v idx -))

(defn- reflect-frame [{:mount/keys [pos axis roll]} {:keys [idx offset]}]
  {:mount/pos (reflect-pos idx offset pos)
   :mount/axis (reflect-dir idx axis)
   :mount/roll (reflect-dir idx roll)})

(defn- reflect-point! [^js p {:keys [idx offset]}]
  (case idx
    0 (set! (.-x p) (reflect-coordinate (.-x p) offset))
    1 (set! (.-y p) (reflect-coordinate (.-y p) offset))
    2 (set! (.-z p) (reflect-coordinate (.-z p) offset))
    nil)
  p)

(def ^:private interface-plane-epsilon 0.08)
(def ^:private interface-normal-cos 0.999)

(defn- v- [[ax ay az] [bx by bz]]
  [(- ax bx) (- ay by) (- az bz)])

(defn- dot [[ax ay az] [bx by bz]]
  (+ (* ax bx) (* ay by) (* az bz)))

(defn- cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by))
   (- (* az bx) (* ax bz))
   (- (* ax by) (* ay bx))])

(defn- length-sq [v]
  (dot v v))

(defn- normalize [[x y z :as v]]
  (let [len (Math/sqrt (length-sq v))]
    (when (pos? len)
      [(/ x len) (/ y len) (/ z len)])))

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
  (normalize (cross (v- b a) (v- c a))))

(defn- triangle-center [points]
  (mapv (fn [idx] (/ (reduce + (map #(nth % idx) points)) 3.0))
        (range 3)))

(defn- point-on-mount-plane? [pos axis p]
  (<= (Math/abs (dot axis (v- p pos))) interface-plane-epsilon))

(defn- interface-triangle? [pos axis points]
  (when-let [normal (triangle-normal points)]
    (and (every? #(point-on-mount-plane? pos axis %) points)
         (>= (Math/abs (dot normal axis)) interface-normal-cos))))

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
                                             (length-sq (v- (triangle-center points) pos)))
                                           triangles)))]
          {:indices (connected-indices (adjacency triangles) start)
           :candidates (count triangles)})))))

(defn- scaled-end [origin dir scale]
  (doto (.clone (v3 origin))
    (.addScaledVector (v3 dir) scale)))

(defn- object-geometry-count [^js obj]
  (let [n (atom 0)]
    (when obj
      (.traverse obj (fn [^js child] (when (.-geometry child) (swap! n inc)))))
    @n))

(defn- current-authoring? [{:keys [authoring]} part-id mesh-key]
  (let [a @authoring]
    (and (= part-id (:part-id a))
         (= mesh-key (:mesh-key a)))))

(defn- sync-authoring-button! [{:keys [authoring]}]
  (when-let [button (.querySelector js/document "[data-authoring-toggle]")]
    (let [active? (current-authoring? {:authoring authoring}
                                      (.getAttribute button "data-part-id")
                                      (.getAttribute button "data-mesh-key"))]
      (.setAttribute button "aria-pressed" (if active? "true" "false"))
      (set! (.-textContent button) (if active? "Done picking" "Pick mount face")))))

(defn- enter-authoring! [{:keys [^js canvas parts authoring current] :as sys} part-id mesh-key]
  (when (and (get @parts part-id)
             (= {:part-id part-id :mesh-key mesh-key} @current))
    (clear-authoring-preview! sys)
    (reset! authoring {:part-id part-id :mesh-key mesh-key})
    (.add (.-classList canvas) "stage__canvas--authoring")
    (sync-authoring-button! sys)))

(defn- exit-authoring! [{:keys [^js canvas authoring repeat] :as sys}]
  (clear-authoring-preview! sys)
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
      (let [part-id (.getAttribute button "data-part-id")
            mesh-key (.getAttribute button "data-mesh-key")
            state (if (current-authoring? sys part-id mesh-key) :exit :enter)]
        (shipyard-event! "authoring" {:state state :part-id part-id :mesh-key mesh-key})))))

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

(defn- line-preview [origin dir length color]
  (let [geometry (doto (three/BufferGeometry.)
                   (.setFromPoints #js [(v3 origin) (scaled-end origin dir length)]))
        material (three/LineBasicMaterial. #js {:color color})]
    (three/Line. geometry material)))

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

(defn- interface-highlight-object [^js obj mount]
  (let [interface-type (interface-colors/type-of mount)]
    (if-let [{:keys [indices candidates]} (interface-facet obj mount)]
      (let [facet-indices (seq indices)]
        {:type interface-type
         :mount-id (:mount/id mount)
         :triangles (count facet-indices)
         :candidates candidates
         :object (face-highlight obj
                                 facet-indices
                                 mount
                                 nil
                                 (color-int interface-type)
                                 0.42)})
      {:type interface-type
       :mount-id (:mount/id mount)
       :triangles 0
       :candidates 0
       :object nil})))

(defn- interface-highlights [^js obj mounts]
  (let [items (keep #(interface-highlight-object obj %) mounts)
        group (three/Group.)]
    (doseq [{:keys [^js object]} items]
      (when object
        (.add group object)))
    {:object group
     :items (mapv #(dissoc % :object) (filter :object items))
     :misses (mapv #(dissoc % :object) (remove :object items))}))

(defn- preview-object [^js obj {:keys [facet-indices frame]} mirror]
  (let [axis (:mount/axis frame)
        roll (:mount/roll frame)
        pos (:mount/pos frame)
        length (preview-length obj)
        highlight (face-highlight obj facet-indices frame nil 0xf0c65a 0.56)
        axis-line (three/ArrowHelper. (v3 axis) (v3 pos) length 0xf0c65a (* length 0.22) (* length 0.08))
        roll-line (line-preview pos roll (* length 0.75) 0x69d2c0)
        mirrored-frame (when mirror (reflect-frame frame mirror))
        group (doto (three/Group.)
                (.add highlight)
                (.add axis-line)
                (.add roll-line))]
    (when mirrored-frame
      (.add group (face-highlight obj facet-indices mirrored-frame mirror 0x79a9ff 0.48))
      (.add group (three/ArrowHelper. (v3 (:mount/axis mirrored-frame))
                                      (v3 (:mount/pos mirrored-frame))
                                      length 0x79a9ff (* length 0.22) (* length 0.08)))
      (.add group (line-preview (:mount/pos mirrored-frame)
                                (:mount/roll mirrored-frame)
                                (* length 0.75) 0x8fd8ff)))
    {:object group :mirror-frame mirrored-frame}))

(defn- mirror-form-values []
  (when-let [form (.querySelector js/document ".mount-wizard__form")]
    (let [plane (input-value form "select[name=mirror-plane]")
          idx (get mirror-plane-index plane)
          offset (parse-finite-double (or (input-value form "input[name=mirror-offset]") "0"))]
      (when (and (checked? form "input[name=mirror]")
                 (= "socket" (input-value form "select[name=kind]"))
                 idx
                 offset)
        {:plane plane :idx idx :offset offset}))))

(defn- install-preview! [{:keys [^js scene parts current authoring preview preview-revision]}
                         {:keys [part-id mesh-key] :as data}]
  (when (and (= {:part-id part-id :mesh-key mesh-key} @current)
             (= {:part-id part-id :mesh-key mesh-key} @authoring))
    (when-let [obj (get @parts part-id)]
      (when-let [{:keys [^js object]} @preview]
        (.remove scene object)
        (dispose-object! object))
      (let [mirror (mirror-form-values)
            {:keys [object mirror-frame]} (preview-object obj data mirror)
            revision (swap! preview-revision inc)]
        (.add scene object)
        (reset! preview (assoc data
                               :object object
                               :revision revision
                               :mirror mirror
                               :mirror-frame mirror-frame))))))

(defn- draw-preview! [sys payload]
  (install-preview! sys (select-keys payload [:part-id :mesh-key :facet-indices :frame
                                              :roll-ambiguous? :roll-source])))

(defn- draw-interfaces! [{:keys [^js scene parts current interfaces] :as sys}
                         {:keys [part-id mesh-key mounts]}]
  (when (= {:part-id part-id :mesh-key mesh-key} @current)
    (clear-interface-highlights! sys)
    (when-let [obj (get @parts part-id)]
      (try
        (let [{:keys [^js object items misses]} (interface-highlights obj mounts)]
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

(defn- post-facet! [{:keys [authoring repeat]} triangle-index]
  (let [target (.getElementById js/document "facet-preview")
        {:keys [part-id mesh-key]} @authoring]
    (when (and target part-id mesh-key)
      (-> (js/fetch "/facet"
                    #js {:method "POST"
                         :headers #js {"Content-Type" "application/x-www-form-urlencoded"}
                         :body (form-body (merge @repeat
                                                 (dom-repeat-values)
                                                 {"part-id" part-id
                                                  "mesh-key" mesh-key
                                                  "triangle-index" (str triangle-index)}))})
          (.then (fn [^js res]
                   (let [trigger (.get (.-headers res) "HX-Trigger")]
                     (-> (.text res)
                         (.then (fn [html]
                                  (trigger-header! trigger)
                                  (set! (.-innerHTML target) html)
                                  (some-> js/window .-htmx (.process target))))))))
          (.catch (fn [e]
                    (js/console.error "shipyard: facet selection failed" e)))))))

(defn- input-value [^js form selector]
  (some-> (.querySelector form selector) .-value))

(defn- checked? [^js form selector]
  (boolean (some-> (.querySelector form selector) .-checked)))

(defn- checked-values [^js form selector]
  (mapv #(.-value %) (array-seq (.querySelectorAll form selector))))

(defn- preview-data [preview-record]
  (select-keys preview-record [:part-id :mesh-key :facet-indices :frame
                               :roll-ambiguous? :roll-source]))

(defn- refresh-preview-from-form! [sys ^js e]
  (let [target (.-target e)
        form (when (and target (.-closest target))
               (.closest target ".mount-wizard__form"))]
    (when (and form @(:preview sys))
      (install-preview! sys (preview-data @(:preview sys))))))

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
                        :accepts (checked-values form "input[name=accepts]:checked")
                        :part-role (input-value form "select[name=part-role]")})
        (reset! repeat nil)))))

(defn- pick-face! [{:keys [^js canvas ^js camera parts authoring ^js raycaster ^js pointer] :as sys} ^js e]
  (when-let [{:keys [part-id]} @authoring]
    (when-let [obj (get @parts part-id)]
      (canvas-pointer! pointer canvas e)
      (.setFromCamera raycaster pointer camera)
      (let [hits (.intersectObject raycaster obj false)]
        (when (pos? (.-length hits))
          (let [face-index (.-faceIndex (aget hits 0))]
            (when (some? face-index)
              (post-facet! sys face-index))))))))

(defn- load-mesh!
  "Fetch, decode, upload, and optionally reframe. Errors are reported and
  swallowed: a part that fails to load must not take the session with it."
  [{:keys [^js scene ^js canvas authoring current repeat] :as sys}
   {:keys [url part-id mesh-key frame mounts]}]
  (-> (js/fetch url)
      (.then (fn [^js res]
               (if (.-ok res)
                 (.arrayBuffer res)
                 (throw (js/Error. (str "mesh request failed: " (.-status res)))))))
      (.then (fn [buf]
               (let [{:keys [bbox-min bbox-max] :as mesh} (wire/decode buf)
                     obj (three/Mesh. (decode->geometry mesh) (material))]
                 (set! (.-name obj) (or part-id url))
                 (set! (.. obj -userData -partId) part-id)
                 (set! (.. obj -userData -meshKey) mesh-key)
                 (clear-authoring-preview! sys)
                 (clear-interface-highlights! sys)
                 (reset! authoring nil)
                 (reset! current {:part-id part-id :mesh-key mesh-key})
                 (reset! repeat nil)
                 (.remove (.-classList canvas) "stage__canvas--authoring")
                 (show-only! sys part-id obj)
                 (draw-interfaces! sys {:part-id part-id
                                        :mesh-key mesh-key
                                        :mounts mounts})
                 (when frame (frame! sys bbox-min bbox-max))
                 (swap! (:status sys) assoc :state :loaded :part-id part-id)
                 (sync-authoring-button! sys)
                 scene)))
      (.catch (fn [e]
                (js/console.error "shipyard: could not load" url e)
                (swap! (:status sys) assoc :state :failed :message (str e))))))

;; --- test hook --------------------------------------------------------------

(defn- preview-stats [{:keys [preview]}]
  (when-let [{:keys [^js object revision part-id mesh-key facet-indices frame
                     mirror mirror-frame roll-ambiguous? roll-source]} @preview]
    (clj->js {:revision revision
              :part-id part-id
              :mesh-key mesh-key
              :facet-indices facet-indices
              :triangles (count facet-indices)
              :position (:mount/pos frame)
              :axis (:mount/axis frame)
              :roll (:mount/roll frame)
              :mirror-visible? (boolean mirror-frame)
              :mirror-plane (:plane mirror)
              :mirror-offset (:offset mirror)
              :mirror-position (:mount/pos mirror-frame)
              :mirror-axis (:mount/axis mirror-frame)
              :mirror-roll (:mount/roll mirror-frame)
              :roll-ambiguous? roll-ambiguous?
              :roll-source (some-> roll-source name)
              :geometries (object-geometry-count object)})))

(defn- interface-stats [{:keys [interfaces]}]
  (when-let [{:keys [part-id mesh-key items misses error]} @interfaces]
    (let [item-stats (fn [{:keys [type mount-id triangles candidates]}]
                       {:type (name type)
                        :mount-id (name mount-id)
                        :triangles triangles
                        :candidates candidates})]
      (clj->js {:part-id part-id
                :mesh-key mesh-key
                :count (count items)
                :misses (count misses)
                :error error
                :items (mapv item-stats items)
                :miss-items (mapv item-stats misses)}))))

(defn stats
  "Scene facts for the E2E suite (§10.3).

  Asserting on WebGL through pixels is brittle - driver, antialiasing and
  timing all move it - so the tests read this instead. Compiled out of release
  builds by `TEST-HOOKS`, so it cannot ship."
  [{:keys [^js renderer ^js camera ^js controls parts status authoring] :as sys}]
  (let [objs (vals @parts)]
    #js {:parts     (clj->js (vec (keys @parts)))
         :vertices  (reduce + 0 (map (fn [^js o] (.. o -geometry -attributes -position -count)) objs))
         :triangles (reduce + 0 (map (fn [^js o] (/ (.. o -geometry -index -count) 3)) objs))
         :draws     (.. renderer -info -render -calls)
         :target    (let [t (.-target controls)] #js [(.-x t) (.-y t) (.-z t)])
         :camera    (let [p (.-position camera)] #js [(.-x p) (.-y p) (.-z p)])
         :materials (clj->js (mapv (fn [^js o] (.getHexString (.. o -material -color))) objs))
         ;; three's own count of geometries live on the GPU, decremented by
         ;; `geometry.dispose()`. The only thing here that is not derived from
         ;; `parts`, and therefore the only one that can tell "removed from the
         ;; scene" from "actually released" - which is what #47's test claimed
         ;; to check and could not.
         :geometries (.. renderer -info -memory -geometries)
         :status    (clj->js (:state @status))
         :authoring (clj->js @authoring)
         :repeat    (clj->js @(:repeat sys))
         :interfaces (interface-stats sys)
         :preview   (preview-stats sys)}))

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
    (.addEventListener body "shipyard:status" #(reset! (:status sys) (payload %)))
    (.addEventListener body "shipyard:authoring" #(authoring! sys (payload %)))
    (.addEventListener body "shipyard:clear-preview" (fn [_] (clear-authoring-preview! sys)))
    (.addEventListener body "shipyard:facet-preview" #(draw-preview! sys (payload %)))
    (.addEventListener body "shipyard:facet-error" (fn [_] (clear-authoring-preview! sys)))
    (.addEventListener body "shipyard:mount-repeat" #(reset! (:repeat sys) (payload %)))
    (.addEventListener body "shipyard:interfaces" #(draw-interfaces! sys (payload %)))
    (.addEventListener body "htmx:afterSwap" (fn [_]
                                               (sync-authoring-button! sys)
                                               (sync-interfaces-from-dom! sys)))
    (.addEventListener body "input" #(refresh-preview-from-form! sys %))
    (.addEventListener body "change" #(refresh-preview-from-form! sys %))
    (.addEventListener body "submit" #(remember-repeat-from-submit! sys %))
    (.addEventListener body "click" #(authoring-toggle! sys %))))

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
                    :controls controls :parts (atom {}) :status (atom {:state :idle})
                    :current (atom nil) :authoring (atom nil) :preview (atom nil)
                    :interfaces (atom nil) :repeat (atom nil)
                    :preview-revision (atom 0)
                    :raycaster (three/Raycaster.) :pointer (three/Vector2.)}]
      (set! (.-outputColorSpace renderer) three/SRGBColorSpace)
      (.setPixelRatio renderer (min 2 (.-devicePixelRatio js/window)))
      (set! (.-background scene) (three/Color. 0x14171c))
      (set! (.-enableDamping controls) true)
      (environment! renderer scene)
      (resize! sys)
      (.observe (js/ResizeObserver. #(resize! sys)) canvas)
      (.addEventListener canvas "click" #(pick-face! sys %))
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
