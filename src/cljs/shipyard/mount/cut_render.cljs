(ns shipyard.mount.cut-render
  (:require ["clipper-lib" :as clipper]
            ["three" :as three]
            [shipyard.mount.cut :as cut]))

(defn recess-rings [mount]
  (let [scale 100000.0
        paths (clj->js (mapv (fn [ring]
                               (mapv (fn [[x y]] {:X (js/Math.round (* scale x)) :Y (js/Math.round (* scale y))})
                                     (cut/project mount ring))) (:mount/outline mount)))
        offset (clipper/ClipperOffset. 5.0 0.25)
        result #js []]
    (.AddPaths offset paths (.. clipper -JoinType -jtMiter) (.. clipper -EndType -etClosedPolygon))
    (.Execute offset result (* (- (get-in mount [:mount/cut :border])) scale))
    (mapv (fn [ring] (mapv (fn [^js p] [(/ (.-X p) scale) (/ (.-Y p) scale)]) (array-seq ring))) (array-seq result))))

(defn lines [mount]
  (let [profiles (case (get-in mount [:mount/cut :kind])
                   :pit (cut/pit-rings mount)
                   :recess [{:frame mount :rings (recess-rings mount)}]
                   [])]
    (vec (mapcat (fn [{:keys [frame rings]}] (cut/wire-lines frame rings (get-in mount [:mount/cut :depth]))) profiles))))

(defn object! [mount]
  (when-let [points (seq (lines mount))]
    (let [geometry (doto (three/BufferGeometry.)
                     (.setFromPoints (into-array (map (fn [[x y z]] (three/Vector3. x y z)) (apply concat points)))))
          object (three/LineSegments. geometry
                                      (three/LineBasicMaterial. #js {:color 0xffdf80 :depthTest false :depthWrite false}))]
      (set! (.-name object) "mount-cut")
      (set! (.-renderOrder object) 1000)
      object)))
