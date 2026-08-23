(ns shipyard.integration.lod-test
  "Real meshoptimizer native calls, so integration rather than unit (§10.1/§10.2).
  This is also the suite that exercises LWJGL natives on Windows CI."
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.fixtures :as f]
            [shipyard.mesh.lod :as lod]
            [shipyard.mesh.stl :as stl]))

(defn- soup [] (stl/parse-bytes (f/->binary-stl (f/uv-sphere 1.0 40 60))))

(deftest tiers-decrease-and-tier-zero-is-lossless
  (let [mesh  (soup)
        tiers (lod/generate mesh)
        counts (mapv :simplified-index-count tiers)]
    (is (= 3 (count tiers)))
    (is (= (* 3 (:triangle-count mesh)) (first counts))
        "tier 0 keeps every index - it is a reordering, not a reduction")
    (is (apply > counts)
        (str "index counts must strictly decrease: " counts))))

(deftest every-tier-is-a-valid-mesh
  (doseq [t (lod/generate (soup))]
    (testing (str "tier " (:ratio t))
      (let [^ints idx (:indices t), v (:vertex-count t)]
        (is (pos? (alength idx)) "a tier must never come back empty")
        (is (zero? (rem (alength idx) 3)))
        (is (every? #(< -1 % v) (vec idx)) "indices reference only compacted vertices")
        (is (= (* 3 v) (alength ^floats (:positions t))))
        (is (= (* 3 v) (alength ^floats (:normals t))))
        (is (every? #(Float/isFinite %) (vec ^floats (:normals t))))))))

(deftest compaction-shrinks-the-vertex-buffer
  (testing "each tier carries only the vertices it uses - otherwise every tier
            would ship the full buffer and the 5% tier would be pointless"
    (let [[t0 t1 t2] (lod/generate (soup))]
      (is (< (:vertex-count t2) (:vertex-count t1) (:vertex-count t0)))
      (is (< (/ (:vertex-count t2) (double (:vertex-count t0))) 0.2)
          "the most aggressive tier should cost well under a fifth of tier 0"))))

(deftest targets-are-actually-reached
  (testing "not merely attempted - a stalled simplify returns a count, it does not throw"
    (let [tiers (lod/generate (soup))
          i0    (double (:simplified-index-count (first tiers)))]
      (doseq [t (rest tiers)]
        (let [achieved (/ (:simplified-index-count t) i0)]
          (is (< achieved (+ (:ratio t) 0.05))
              (format "tier %.2f only reached %.4f" (:ratio t) achieved)))))))

(deftest prune-is-not-enabled
  (testing "meshopt_SimplifyPrune at target_error 1.0 returns zero indices and
            reports it as success (issue #6), so a very permissive error budget
            must still produce geometry"
    (doseq [t (lod/generate (soup) {:target-error 1.0})]
      (is (pos? (:index-count t))
          (str "tier " (:ratio t) " came back empty - Prune may have been enabled")))))

(deftest crease-angle-and-tiers-are-configurable
  (let [mesh (soup)
        two  (lod/generate mesh {:tiers [1.0 0.5]})
        soft (lod/generate mesh {:crease-deg 179})
        hard (lod/generate mesh {:crease-deg 1})]
    (is (= 2 (count two)))
    (is (<= (:vertex-count (first soft)) (:vertex-count (first hard)))
        "a permissive crease angle shares more vertices")))

(deftest repeated-generation-does-not-leak
  (testing "smoke test for native buffer lifetime: with-native must free on every
            path. Run with -Dorg.lwjgl.util.DebugAllocator=true for real tracking."
    (let [mesh (stl/parse-bytes (f/->binary-stl (f/uv-sphere 1.0 16 24)))]
      (dotimes [_ 25] (lod/generate mesh))
      (is true "25 generations completed without exhausting native memory"))))
