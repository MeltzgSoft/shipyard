(ns shipyard.pitting.geometry
  "Pure face insets and solid subtraction. Source geometry stays in millimetres."
  (:require [shipyard.math :as math]
            [shipyard.mount.cut :as cut])
  (:import [eu.mihosoft.jcsg CSG CSG$OptType Polygon Vertex]
           [eu.mihosoft.vvecmath Vector3d]
           [org.locationtech.jts.geom Coordinate Geometry GeometryFactory]
           [org.locationtech.jts.operation.buffer BufferOp BufferParameters]
           [org.locationtech.jts.triangulate.polygon PolygonTriangulator]
           [java.nio ByteBuffer ByteOrder]))

(defn mesh-triangles
  ([{:keys [positions indices] :as mesh}]
   (mesh-triangles mesh (range (quot (count (or indices positions)) (if indices 3 9)))))
  ([{:keys [^floats positions ^ints indices]} triangle-ids]
   (let [vertex (fn [i] (mapv #(double (aget positions (+ (* 3 i) %))) (range 3)))]
     (mapv (fn [triangle]
             (mapv (fn [corner]
                     (let [i (+ (* triangle 3) corner)]
                       (vertex (if indices (aget indices i) i)))) (range 3))) triangle-ids))))

(defn inset [mount]
  (let [factory (GeometryFactory.)
        polygons (map (fn [ring]
                        (let [points (cut/project mount ring)]
                          (.createPolygon factory
                                          ^"[Lorg.locationtech.jts.geom.Coordinate;" (into-array Coordinate
                                                                                                 (map (fn [[x y]] (Coordinate. x y))
                                                                                                      (conj points (first points)))))))
                      (:mount/outline mount))
        ^Geometry shape (reduce (fn [^Geometry a ^Geometry b] (.symDifference a b)) polygons)
        params (doto (BufferParameters.) (.setJoinStyle BufferParameters/JOIN_MITRE))
        result (when shape (BufferOp/bufferOp shape (double (- (get-in mount [:mount/cut :border]))) params))]
    (when (or (nil? result) (.isEmpty ^Geometry result))
      (throw (ex-info "The recess border consumes the face. Reduce border or pick the face again." {})))
    result))

(defn- coordinates [^Geometry ring]
  (mapv (fn [^Coordinate c] [(.getX c) (.getY c)]) (butlast (.getCoordinates ring))))

(defn- polygon-rings [^org.locationtech.jts.geom.Polygon polygon]
  (into [(coordinates (.getExteriorRing polygon))]
        (map #(coordinates (.getInteriorRingN polygon %)) (range (.getNumInteriorRing polygon)))))

(defn profiles [mount]
  (case (get-in mount [:mount/cut :kind])
    :pit (cut/pit-rings mount)
    :recess (let [shape (inset mount)]
              [{:frame mount
                :rings (vec (mapcat #(polygon-rings (.getGeometryN ^Geometry shape %))
                                    (range (.getNumGeometries ^Geometry shape))))
                :shape shape}])
    []))

(defn- vector3 ^Vector3d [[x y z]] (Vector3d/xyz x y z))

(defn- polygon ^Polygon [points]
  (Polygon/fromPoints ^java.util.List (mapv vector3 points)))

(defn- ccw [ring]
  (let [area (reduce + (map (fn [[[x y] [u v]]] (- (* x v) (* y u)))
                            (map vector ring (concat (rest ring) [(first ring)]))))]
    (if (neg? area) (vec (reverse ring)) ring)))

(defn- cutter ^CSG [{:keys [frame rings shape]} depth]
  (let [factory (GeometryFactory.)
        shape (or shape
                  (.createPolygon factory ^"[Lorg.locationtech.jts.geom.Coordinate;" (into-array Coordinate
                                                                                                 (map (fn [[x y]] (Coordinate. x y))
                                                                                                      (conj (first rings) (ffirst rings))))))
        caps (PolygonTriangulator/triangulate shape)
        top (fn [p] (cut/point frame p 0.02))
        bottom (fn [p] (cut/point frame p (- depth)))
        triangles (map #(ccw (coordinates (.getGeometryN caps %))) (range (.getNumGeometries caps)))
        walls (mapcat (fn [ring]
                        ;; Exterior is CCW, holes CW: interior remains on the left.
                        (for [[a b] (map vector ring (concat (rest ring) [(first ring)]))]
                          (polygon [(bottom a) (bottom b) (top b) (top a)])))
                      ;; JTS rings use exterior CW, interior CCW.
                      (if shape
                        (mapcat (fn [i]
                                  (let [^org.locationtech.jts.geom.Polygon p (.getGeometryN ^Geometry shape i)
                                        rs (polygon-rings p)]
                                    (cons (ccw (first rs)) (map #(vec (reverse (ccw %))) (rest rs)))))
                                (range (.getNumGeometries ^Geometry shape)))
                        rings))]
    (CSG/fromPolygons ^java.util.List
     (vec (concat (map #(polygon (mapv top %)) triangles)
                  (map #(polygon (mapv bottom (reverse %))) triangles) walls)))))

(defn- valid-triangle? [[a b c]]
  (and (every? math/finite-number? (apply concat [a b c]))
       (> (math/length (math/cross (math/subtract b a) (math/subtract c a))) 1.0e-12)))

(defn cancel-internal-faces
  "Cancel exact opposite-wound pairs for solid subtraction, retaining source order.
  Same-facing, ambiguous and degenerate duplicates stay for validation to reject."
  [triangles]
  (let [internal (into #{}
                       (mapcat (fn [group]
                                 (when (= 2 (count group))
                                   (let [[[a b c :as face] other] group]
                                     (when (and (valid-triangle? face)
                                                (contains? #{[a b c] [b c a] [c a b]} (vec (reverse other))))
                                       group)))))
                       (vals (group-by #(vec (sort %)) triangles)))]
    (into [] (remove internal) triangles)))

(defn- closed-surface? [triangles]
  (let [edges (frequencies (mapcat (fn [[a b c]] [[a b] [b c] [c a]]) triangles))]
    (and (seq triangles)
         (pos? (reduce + (map (fn [[a b c]] (math/dot a (math/cross b c))) triangles)))
         (every? valid-triangle? triangles)
         ;; A closed oriented surface can have several sheets meeting along an
         ;; edge. They remain closed when every directed use has an opposing
         ;; use; requiring exactly two faces rejects edge-touching solids.
         (= (count triangles) (count (set (map #(vec (sort %)) triangles))))
         (every? (fn [[[a b] count]] (= count (get edges [b a]))) edges))))

(defn closed-source? [triangles]
  (closed-surface? (cancel-internal-faces triangles)))

(defn subtract
  "Subtract every enabled mount cut from the original closed source mesh."
  [mesh mounts]
  (let [triangles (cancel-internal-faces (mesh-triangles mesh))
        _ (when-not (closed-surface? triangles)
            (throw (ex-info "Pitting requires a closed source mesh with consistent outward faces. Repair the STL before generating cuts." {})))
        source (.optimization (CSG/fromPolygons ^java.util.List (mapv polygon triangles)) CSG$OptType/POLYGON_BOUND)
        result (reduce (fn [^CSG solid mount]
                         (reduce (fn [^CSG solid profile]
                                   (let [tool (cutter profile (get-in mount [:mount/cut :depth]))]
                                     (if (.intersects (.getBounds solid) (.getBounds tool))
                                       (.difference solid tool)
                                       solid)))
                                 solid (profiles mount))) source mounts)]
    (vec (for [^Polygon p (.getPolygons ^CSG result)
               ^Polygon triangle (.toTriangles p)]
           (mapv (fn [^Vertex v]
                   (let [^Vector3d p (.-pos v)] [(.x p) (.y p) (.z p)])) (.-vertices triangle))))))

(defn binary-stl [triangles]
  (let [buffer (doto (ByteBuffer/allocate (+ 84 (* 50 (count triangles))))
                 (.order ByteOrder/LITTLE_ENDIAN))]
    (.position buffer 80)
    (.putInt buffer (count triangles))
    (doseq [[a b c :as tri] triangles]
      (let [normal (or (math/normalize (math/cross (math/subtract b a) (math/subtract c a))) [0.0 0.0 0.0])]
        (doseq [component (concat normal (apply concat tri))] (.putFloat buffer (float component)))
        (.putShort buffer (short 0))))
    (.array buffer)))
