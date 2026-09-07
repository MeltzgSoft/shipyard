(ns shipyard.mesh.volume
  "Pure volume calculations over triangle soups.")

(defn signed
  "Signed volume of `triangle-count` packed float triangles."
  ^double [^floats positions ^long triangle-count]
  (loop [triangle 0
         total 0.0]
    (if (>= triangle triangle-count)
      (/ total 6.0)
      (let [offset (* triangle 9)
            ax (aget positions offset)
            ay (aget positions (+ offset 1))
            az (aget positions (+ offset 2))
            bx (aget positions (+ offset 3))
            by (aget positions (+ offset 4))
            bz (aget positions (+ offset 5))
            cx (aget positions (+ offset 6))
            cy (aget positions (+ offset 7))
            cz (aget positions (+ offset 8))]
        (recur (inc triangle)
               (+ total (- (+ (* ax by cz) (* ay bz cx) (* az bx cy))
                           (+ (* az by cx) (* ay bx cz) (* ax bz cy)))))))))
