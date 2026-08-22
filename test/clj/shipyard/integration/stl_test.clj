(ns shipyard.integration.stl-test
  "Issue #8 acceptance for the paths that touch disk: memory-mapped reads, the
  committed ASCII fixture, and the allocation claim."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.fixtures :as f]
            [shipyard.mesh.stl :as stl])
  (:import [java.lang.management ManagementFactory]))

(defn- temp-stl ^java.io.File [^bytes b suffix]
  (let [file (java.io.File/createTempFile "shipyard-" suffix)]
    (.deleteOnExit file)
    (with-open [o (io/output-stream file)] (.write o b))
    file))

(deftest mmap-binary-roundtrip
  (let [tris (f/uv-sphere 1.0 12 16)
        file (temp-stl (f/->binary-stl tris) ".stl")
        m    (stl/parse-file file)]
    (is (= (count tris) (:triangle-count m)))
    (is (= (* 9 (count tris)) (alength ^floats (:positions m))))))

(deftest committed-ascii-fixture
  (testing "an ASCII STL with CRLF endings, as found in the real library"
    (let [m (stl/parse-file (io/file "test/fixtures/cube-ascii-crlf.stl"))]
      (is (= 12 (:triangle-count m)))
      (is (= [-1.0 -1.0 -1.0] (mapv float (:bbox-min m))))
      (is (= [1.0 1.0 1.0] (mapv float (:bbox-max m)))))))

(deftest truncated-file-on-disk
  (let [b     (f/->binary-stl (f/cube 2.0))
        short (java.util.Arrays/copyOf b (- (alength b) 20))
        file  (temp-stl short ".stl")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"truncated or corrupt"
                          (stl/parse-file file)))))

(deftest allocates-no-per-triangle-objects
  (testing "heap allocation stays within a small multiple of the output array"
    (let [tris  (f/uv-sphere 1.0 40 60)          ; 4,680 triangles
          bytes (f/->binary-stl tris)
          n     (count tris)
          out   (* 9 n 4)                        ; the float[] we must produce
          mx    (ManagementFactory/getThreadMXBean)
          tid   (.getId (Thread/currentThread))
          alloc (fn []
                  (.getThreadAllocatedBytes
                   ^com.sun.management.ThreadMXBean mx tid))]
      (dotimes [_ 3] (stl/parse-bytes bytes))    ; warm up, load classes
      (let [before (alloc)
            _      (stl/parse-bytes bytes)
            used   (- (alloc) before)]
        (is (< used (* 3 out))
            (format "parsing %d triangles allocated %d bytes for a %d-byte result (limit %d). A per-triangle object would put this an order of magnitude higher."
                    n used out (* 3 out)))))))

(def ^:private known-ascii
  "The one ASCII STL in the real library (issue #3). Absent in CI, so this test
  reports a skip rather than failing - the committed fixture above is what keeps
  the ASCII path covered everywhere."
  (io/file (System/getProperty "user.home")
           "Documents/3D_models/BFG/Toaster Mechanics Fleet Bundle/Escort/Toaster Stalker Prow/unsupported.stl"))

(deftest real-ascii-file-from-the-library
  ;; The skip branch must still assert: kaocha fails a test that runs no
  ;; assertions ("Test ran without assertions"), so a bare println here turns an
  ;; intentional skip into a red build on any machine without the library.
  (if-not (.isFile known-ascii)
    (is true "real library not present; test/fixtures/cube-ascii-crlf.stl covers the ASCII path")
    (let [m (stl/parse-file known-ascii)]
      (testing "its binary header claims 1.8 billion triangles and must be ignored"
        (is (pos? (:triangle-count m)))
        (is (< (:triangle-count m) 1000000)))
      (is (= (* 9 (:triangle-count m)) (alength ^floats (:positions m))))
      (is (every? #(Float/isFinite %) (concat (:bbox-min m) (:bbox-max m))))
      (println (format "  real ASCII file: %,d triangles, bbox %s -> %s"
                       (:triangle-count m) (:bbox-min m) (:bbox-max m))))))
