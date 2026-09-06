(ns shipyard.mesh.stl-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.fixtures :as f]
            [shipyard.mesh.stl :as stl]))

(defn- close? [a b] (< (abs (- (double a) (double b))) 1e-5))

(deftest zero-triangle-header-says-so
  (testing "a valid binary STL declaring no triangles is refused with a message
            about the file rather than about a float"
    ;; Found by the canary (#18). The bbox accumulators stay at their infinities
    ;; when there is nothing to accumulate, and `(float Double/POSITIVE_INFINITY)`
    ;; threw "Value out of range for float: Infinity" - true, and useless.
    (let [empty-stl (f/->binary-stl [])]
      (is (= 84 (alength empty-stl)) "84 + 50*0, so it takes the binary path")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"zero triangles"
                            (stl/parse-bytes empty-stl)))
      (testing "and says which case it is in data, so callers need not grep prose"
        (is (= 0 (:triangle-count (ex-data (try (stl/parse-bytes empty-stl)
                                                (catch clojure.lang.ExceptionInfo e e))))))))))

(deftest binary-cube
  (let [tris (f/cube 2.0)
        m    (stl/parse-bytes (f/->binary-stl tris))]
    (is (= 12 (:triangle-count m)))
    (is (= (* 12 9) (alength ^floats (:positions m))))
    (testing "bbox is exact, not approximate"
      (is (= [-1.0 -1.0 -1.0] (mapv float (:bbox-min m))))
      (is (= [1.0 1.0 1.0] (mapv float (:bbox-max m)))))
    (testing "vertices survive in file order"
      (let [p (:positions m)
            first-tri (partition 3 (take 9 (vec p)))]
        (is (= (mapv #(mapv float %) (first tris))
               (mapv #(mapv float %) first-tri)))))))

(deftest binary-sphere
  (let [rings 8 segments 12
        tris  (f/uv-sphere 1.0 rings segments)
        m     (stl/parse-bytes (f/->binary-stl tris))]
    (is (= (* 2 segments (dec rings)) (:triangle-count m))
        "poles emit one triangle per segment, bands emit two")
    (is (every? #(close? 1.0 (abs %)) (concat (:bbox-min m) (:bbox-max m)))
        "a unit sphere's bbox touches +/-1 on every axis")))

(deftest ascii-matches-binary
  (let [tris (f/cube 2.0)
        b    (stl/parse-bytes (f/->binary-stl tris))
        a    (stl/parse-bytes (f/->ascii-stl tris))]
    (is (= (:triangle-count b) (:triangle-count a)))
    (is (= (mapv float (:bbox-min b)) (mapv float (:bbox-min a))))
    (is (every? true? (map close? (vec ^floats (:positions b)) (vec ^floats (:positions a)))))))

(deftest ascii-with-crlf
  (testing "the one real ASCII file in this library has CRLF endings"
    (let [tris (f/cube 2.0)
          m    (stl/parse-bytes (f/->ascii-stl tris "\r\n"))]
      (is (= 12 (:triangle-count m)))
      (is (= [-1.0 -1.0 -1.0] (mapv float (:bbox-min m)))))))

(deftest header-beginning-with-solid-is-still-binary
  (testing "the classic trap: a binary STL whose 80-byte header starts with 'solid'"
    (let [tris (f/cube 2.0)
          m    (stl/parse-bytes (f/->binary-stl tris "solid exported by some tool"))]
      (is (= 12 (:triangle-count m))
          "detection must key on size == 84 + 50n, never on the leading text"))))

(deftest size-mismatch-is-detected
  (testing "a declared count that does not match the file size is never trusted"
    (let [b (f/->binary-stl (f/cube 2.0))
          ;; claim 1.8 billion triangles, as the real ASCII file's header does
          buf (doto (java.nio.ByteBuffer/wrap b)
                (.order java.nio.ByteOrder/LITTLE_ENDIAN)
                (.putInt 80 (unchecked-int 1814065765)))]
      (is (not= (alength b) (stl/expected-size 1814065765)))
      (is (thrown? clojure.lang.ExceptionInfo (stl/parse-bytes (.array buf)))
          "falls through to ASCII, finds no vertices, and reports clearly"))))

(deftest truncated-file-fails-clearly
  (let [b     (f/->binary-stl (f/cube 2.0))
        short (java.util.Arrays/copyOf b (- (alength b) 37))
        e     (is (thrown? clojure.lang.ExceptionInfo (stl/parse-bytes short)))]
    (testing "an actionable message, not an IndexOutOfBoundsException"
      (is (re-find #"truncated or corrupt" (ex-message e)))
      (is (re-find #"not valid binary" (ex-message e)))
      (is (= (alength short) (:size (ex-data e)))))))

(deftest ascii-with-partial-triangle-fails
  (testing "a vertex count that is not a whole number of triangles"
    (let [text "solid x\n vertex 0 0 0\n vertex 1 0 0\n vertex 0 1 0\n vertex 2 2 2\n"
          e    (is (thrown? clojure.lang.ExceptionInfo
                            (stl/parse-bytes (.getBytes text "US-ASCII"))))]
      (is (= 4 (:vertices (ex-data e)))))))

(deftest empty-input-fails-clearly
  (is (thrown? clojure.lang.ExceptionInfo (stl/parse-bytes (byte-array 0)))))
