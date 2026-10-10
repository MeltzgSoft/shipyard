(ns shipyard.paint.picking
  "Live-scene picking geometry over immutable backend-expanded positions."
  (:require ["three" :as three]))

(def vertex-shader
  "uniform uint idOffset; flat out uint triangleId; void main(){ triangleId=uint(gl_VertexID/3)+idOffset; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }")
(def fragment-shader
  "flat in uint triangleId; out vec4 outputColor; void main(){ uvec3 rgb=uvec3(triangleId&255u,(triangleId>>8u)&255u,(triangleId>>16u)&255u); outputColor=vec4(vec3(rgb)/255.0,1.0); }")

(defn range-end [start triangles]
  (let [end (+ start triangles)]
    (when (> end 16777216) (throw (js/Error. "Model exceeds the brush face-ID capacity.")))
    end))

(defn geometry!
  "One shared position-only GPU geometry per live immutable source. Ordinals are
  implicit in backend nonindexed vertex order; no CPU ID attribute is allocated."
  [^js object]
  (or (.. object -userData -brushPickingGeometry)
      (let [^js topology (.. object -geometry -userData -preparedTopology)]
        (when-not topology (throw (js/Error. "Source picking preparation is unavailable.")))
        (let [geometry (or (.-pickingGeometry topology)
                           (let [geometry (three/BufferGeometry.)]
                             (.setAttribute geometry "position" (three/BufferAttribute. (.-positions topology) 3))
                             (set! (.-pickingGeometry topology) geometry)
                             geometry))]
          (set! (.-pickingUsers topology) (inc (or (.-pickingUsers topology) 0)))
          (set! (.. object -userData -brushPickingTopology) topology)
          (set! (.. object -userData -brushPickingGeometry) geometry)
          geometry))))

(defn dispose-object! [^js object]
  (when-let [^js topology (.. object -userData -brushPickingTopology)]
    (set! (.-pickingUsers topology) (dec (.-pickingUsers topology)))
    (when (zero? (.-pickingUsers topology))
      (.dispose (.-pickingGeometry topology))
      (set! (.-pickingGeometry topology) nil))
    (set! (.. object -userData -brushPickingTopology) nil)
    (set! (.. object -userData -brushPickingGeometry) nil)))

(defn stats [parts]
  (let [sources (distinct (keep #(.. ^js % -userData -brushPickingTopology) (vals parts)))]
    {:geometries (count sources)
     :instances (count (filter #(.. ^js % -userData -brushPickingGeometry) (vals parts)))
     :position-bytes (reduce + 0 (map #(.-byteLength (.-positions ^js %)) sources))
     :source-buffer-bytes (reduce + 0 (map #(.. ^js % -positions -buffer -byteLength) sources))
     :id-attribute-bytes 0
     :geometry-ids (mapv #(.. ^js % -pickingGeometry -uuid) sources)
     :last-capture (some #(.. ^js % -userData -brushPickingTiming) (vals parts))}))
