(ns shipyard.regions.mirror
  "Transient, part-scoped mirror controls and reflected brush picking."
  (:require ["three" :as three]
            [shipyard.math :as math]
            [shipyard.paint.brush :as brush]
            [shipyard.paint.render :as render]
            [shipyard.regions.center :as center]
            [shipyard.regions.symmetry :as symmetry]))

(defn- field [^js form name] (.namedItem (.-elements form) name))

(defn bounds! [^js object orientation]
  (let [cached (.. object -userData -regionMirrorBounds)]
    (if (and cached (= orientation (:orientation cached))) (:bounds cached)
        (let [_ (.updateWorldMatrix object true false)
              box (.setFromObject (three/Box3.) object true)
              bounds [(vec (.toArray (.-min box))) (vec (.toArray (.-max box)))]]
          (set! (.. object -userData -regionMirrorBounds) {:orientation orientation :bounds bounds})
          bounds))))

(defn offset!
  "Shared automatic plane for picking and guide. Cache per source/orientation/axis;
  color-buffer replacement during painting does not change the surface geometry."
  [^js object orientation axis explicit]
  (if (some? explicit) explicit
      (let [key [(.. object -userData -meshKey) orientation axis]
            cached (.. object -userData -regionMirrorCenters)]
        (if (contains? cached key) (get cached key)
            (let [bounds (bounds! object orientation)
                  geometry (.-geometry object)
                  point (three/Vector3.)
                  triangles (map (fn [i]
                                   (mapv (fn [p]
                                           (.fromArray point (to-array p))
                                           (.applyMatrix4 point (.-matrixWorld object))
                                           (vec (.toArray point)))
                                         (render/triangle-points geometry i)))
                                 (range (render/triangle-count geometry)))
                  offset (center/estimate triangles bounds axis)]
              (set! (.. object -userData -regionMirrorCenters) (assoc cached key offset))
              offset)))))

(defn install! [{:keys [current] :as sys}]
  (let [settings (atom nil)
        picking (atom nil)
        form! #(.getElementById js/document "region-stroke")
        ensure! (fn [form]
                  (let [key [(.-value (field form "part-id")) (.-value (field form "mesh-key")) (:orientation @current)]]
                    (when (not= key (:key @settings))
                      (reset! settings {:key key :enabled false :axis :x :offset ""}))))
        restore! (fn []
                   (when-let [form (form!)]
                     (ensure! form)
                     (let [{:keys [enabled axis offset]} @settings]
                       (set! (.-checked (field form "mirror")) enabled)
                       (set! (.-value (field form "mirror-axis")) (name axis))
                       (set! (.-value (field form "mirror-offset")) offset)
                       (set! (.-disabled (field form "mirror-axis")) (not enabled))
                       (set! (.-disabled (field form "mirror-offset")) (not enabled)))))]
    (.addEventListener js/document "htmx:afterSwap" (fn [_] (restore!)))
    (.addEventListener js/document "shipyard:part-orientation" (fn [_] (restore!)))
    (.addEventListener js/document "change"
                       (fn [^js event]
                         (when-let [form (form!)]
                           (when (and (.contains form (.-target event))
                                      (#{"mirror" "mirror-axis" "mirror-offset"} (.. event -target -name)))
                             (ensure! form)
                             (swap! settings assoc :enabled (.-checked (field form "mirror"))
                                    :axis (keyword (.-value (field form "mirror-axis")))
                                    :offset (if (= "mirror-axis" (.. event -target -name)) ""
                                                (.-value (field form "mirror-offset"))))
                             (restore!)))))
    {:restore! restore!
     :begin! (fn [slot ^js object]
               (when-let [form (form!)]
                 (when (ensure! form) (restore!))
                 (let [{:keys [enabled axis]} @settings
                       offset (.-value (field form "mirror-offset"))]
                   ;; Pointerdown precedes blur/change on a focused number input.
                   (swap! settings assoc :offset offset)
                   (when enabled
                     (let [offset (when (seq offset) (math/parse-finite-double offset))]
                       (when (or (.. (field form "mirror-offset") -validity -badInput)
                                 (and (seq (:offset @settings)) (nil? offset)))
                         (throw (ex-info "Enter a finite mirror plane offset." {:type :mirror-input})))
                       (let [orientation (:orientation @current)
                             transform (symmetry/reflection-matrix (bounds! object orientation) axis
                                                                   (offset! object orientation axis offset))]
                         (brush/cached-visible-buffer! picking (assoc sys :picking-transform transform) slot)))))))}))
