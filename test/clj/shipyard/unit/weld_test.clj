(ns shipyard.unit.weld-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.fixtures :as f]
            [shipyard.mesh.stl :as stl]
            [shipyard.mesh.weld :as w]))

(defn- welded [tris & {:keys [crease-deg] :or {crease-deg 35}}]
  (w/weld (stl/parse-bytes (f/->binary-stl tris)) {:crease-deg crease-deg}))

(deftest cube-welds-then-splits-at-every-edge
  (let [r (welded (f/cube 2.0))]
    (is (= 8 (:welded-vertex-count r))
        "eight distinct corner positions")
    (is (= 24 (:vertex-count r))
        "every cube edge is a 90-degree crease, so each corner carries three normals")
    (is (= 36 (alength ^ints (:indices r))))))

(deftest crease-angle-is-what-splits
  (testing "a threshold permissive enough to smooth 90 degrees collapses the cube"
    (is (= 8 (:vertex-count (welded (f/cube 2.0) :crease-deg 179)))))
  (testing "and a strict one splits it"
    (is (= 24 (:vertex-count (welded (f/cube 2.0) :crease-deg 1)))))
  (testing "the threshold is a parameter, not a constant"
    (is (not= (:vertex-count (welded (f/cube 2.0) :crease-deg 179))
              (:vertex-count (welded (f/cube 2.0) :crease-deg 35))))))

(deftest sphere-welds-to-the-euler-bound
  (doseq [[rings segments] [[8 12] [16 24] [32 48]]]
    (let [tris (f/uv-sphere 1.0 rings segments)
          r    (welded tris)
          t    (count tris)]
      (testing (str rings "x" segments)
        (is (= (+ (/ t 2) 2) (:welded-vertex-count r))
            "a closed triangulated surface has V = F/2 + 2 exactly")
        (is (= (:welded-vertex-count r) (:vertex-count r))
            "crease splitting is a no-op on a smooth closed surface")
        (is (< 0.49 (double (/ (:vertex-count r) t)) 0.52))))))

(deftest sphere-normals-point-outward
  (let [r (welded (f/uv-sphere 1.0 16 24))
        ^floats p (:positions r)
        ^floats n (:normals r)]
    (dotimes [i (:vertex-count r)]
      (let [d (+ (* (aget p (* 3 i)) (aget n (* 3 i)))
                 (* (aget p (+ (* 3 i) 1)) (aget n (+ (* 3 i) 1)))
                 (* (aget p (+ (* 3 i) 2)) (aget n (+ (* 3 i) 2))))]
        (is (> d 0.99)
            (str "vertex " i " normal is not radial: pos.normal = " d))))))

(deftest fixture-winding-is-consistent
  (testing "guards the fixtures themselves - a reversed pole fan fakes a crease
            and quietly changes every downstream number"
    (doseq [[label tris] [["cube" (f/cube 2.0)]
                          ["sphere" (f/uv-sphere 1.0 8 12)]]]
      (let [m  (stl/parse-bytes (f/->binary-stl tris))
            wp (w/weld-positions (:positions m) (* 3 (:triangle-count m)))
            g  (w/face-geometry (:positions wp) (:indices wp) (:triangle-count m))
            ^floats nrm (:normals g)
            ^floats pos (:positions wp)
            ^ints idx (:indices wp)
            inward (count
                    (for [fi (range (:triangle-count m))
                          :let [a (* 3 (aget idx (* 3 fi)))
                                b (* 3 (aget idx (+ (* 3 fi) 1)))
                                c (* 3 (aget idx (+ (* 3 fi) 2)))
                                cx (/ (+ (aget pos a) (aget pos b) (aget pos c)) 3.0)
                                cy (/ (+ (aget pos (+ a 1)) (aget pos (+ b 1)) (aget pos (+ c 1))) 3.0)
                                cz (/ (+ (aget pos (+ a 2)) (aget pos (+ b 2)) (aget pos (+ c 2))) 3.0)
                                d (+ (* cx (aget nrm (* 3 fi)))
                                     (* cy (aget nrm (+ (* 3 fi) 1)))
                                     (* cz (aget nrm (+ (* 3 fi) 2))))]
                          :when (neg? d)] fi))]
        (is (zero? inward) (str label " has " inward " inward-facing normals"))))))

(deftest indices-stay-in-range
  (let [r (welded (f/uv-sphere 1.0 12 16))
        ^ints idx (:indices r)
        v (:vertex-count r)]
    (is (every? #(< -1 % v) (vec idx)))
    (is (zero? (rem (alength idx) 3)))))

(deftest degenerate-faces-do-not-poison-normals
  (testing "a zero-area triangle must not put NaN into its neighbours' normals"
    ;; Appended to a real surface rather than tested on three loose triangles:
    ;; that is how degenerates actually appear in exported STLs, and a handful
    ;; of triangles has no meaningful vertex ratio.
    (let [sphere (f/uv-sphere 1.0 12 16)
          bad    [[0.5 0.5 0.5] [0.6 0.5 0.5] [0.6 0.5 0.5]]   ; zero area
          r      (welded (conj (vec sphere) bad))]
      (is (every? #(Float/isFinite %) (vec ^floats (:normals r)))
          "no NaN anywhere in the normal buffer")
      (is (every? #(Float/isFinite %) (vec ^floats (:positions r)))))))

(deftest ratio-guard
  (testing "a ratio at or above the fail line throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"welding did not take effect"
                          (w/check-ratio 30000 10000 "soup"))))
  (testing "the warn line is 1.25, not 1.0 - real hulls reach 1.07 in normal operation"
    (is (= 1.07 (w/check-ratio 10700 10000 "hull")))
    (is (= 2.49 (w/check-ratio 24900 10000 "extreme"))))
  (testing "trivially small meshes are exempt: the ratio is meaningless there"
    (is (= 3.0 (w/check-ratio 27 9 "nine triangles"))))
  (testing "unwelded soup is exactly the case this catches"
    (is (thrown? clojure.lang.ExceptionInfo (w/check-ratio 3000 1000 "unwelded")))))
