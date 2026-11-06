(ns shipyard.mount-angle-fixture
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [shipyard.fixtures :as fixtures]))

(def id "Fleet/Cruiser/Ball joint")
(defn- point [angle y]
  (mapv #(double (float %)) [(* 5 (Math/sin (Math/toRadians angle))) y (* 5 (Math/cos (Math/toRadians angle)))]))
(def triangles
  (vec (mapcat (fn [angle]
                 (let [a (point angle -2) b (point (+ angle 15) -2)
                       c (point (+ angle 15) 2) d (point angle 2)]
                   [[a b c] [a c d]])) (range 0 90 15))))
(def gentle-triangles
  (vec (mapcat (fn [angle]
                 (let [a (point angle -2) b (point (+ angle 0.5) -2)
                       c (point (+ angle 0.5) 2) d (point angle 2)]
                   [[a b c] [a c d]])) (map #(* 0.5 %) (range 6)))))

(defn build-library!
  ([root] (build-library! root triangles))
  ([root triangles]
   (let [file (io/file (str root) id "unsupported.stl")]
     (fs/create-dirs (.getParentFile file))
     (with-open [out (io/output-stream file)] (.write out ^bytes (fixtures/->binary-stl triangles))))
   root))
