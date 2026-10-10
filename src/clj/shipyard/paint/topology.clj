(ns shipyard.paint.topology
  "Pure paint-ready source geometry and sorted source-space identity index."
  (:require [shipyard.paint.faces :as faces])
  (:import [java.nio ByteBuffer ByteOrder]))

(defn triangle-points [{:keys [positions indices]} triangle]
  (mapv (fn [corner]
          (let [vertex (nth indices (+ (* triangle 3) corner))]
            (mapv #(nth positions (+ (* vertex 3) %)) (range 3)))) (range 3)))

(defn triangle-normal [points]
  (let [[a b c] points u (mapv - b a) v (mapv - c a)
        normal [(- (* (u 1) (v 2)) (* (u 2) (v 1)))
                (- (* (u 2) (v 0)) (* (u 0) (v 2)))
                (- (* (u 0) (v 1)) (* (u 1) (v 0)))]
        length (Math/sqrt (reduce + (map #(* % %) normal)))]
    (if (pos? length) (mapv #(/ % length) normal) [0.0 0.0 0.0])))

(defn encode
  "Version 1: header [version, N], Float32 positions/normals 9N each,
  UInt32 canonical key words 9N, then UInt32 N source indices sorted by key.
  Triangle order and Float32 bits exactly match tier0 source geometry."
  [{:keys [positions normals indices] :as mesh}]
  (let [n (quot (count indices) 3)
        keys (mapv #(faces/face-key (triangle-points mesh %)) (range n))
        buffer (doto (ByteBuffer/allocate (+ 8 (* n 112))) (.order ByteOrder/LITTLE_ENDIAN))]
    (.putInt buffer 1) (.putInt buffer n)
    (doseq [index indices axis (range 3)] (.putFloat buffer (float (nth positions (+ (* index 3) axis)))))
    (if normals
      (doseq [index indices axis (range 3)] (.putFloat buffer (float (nth normals (+ (* index 3) axis)))))
      (doseq [triangle (range n) normal (repeat 3 (triangle-normal (triangle-points mesh triangle))) value normal]
        (.putFloat buffer (float value))))
    (doseq [key keys word (range 9)]
      (.putInt buffer (unchecked-int (Long/parseLong (subs key (* word 8) (* (inc word) 8)) 16))))
    (doseq [triangle (sort-by #(nth keys %) (range n))] (.putInt buffer (int triangle)))
    (.array buffer)))
