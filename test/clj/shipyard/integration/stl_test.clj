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

(deftest binary-fixture-built-from-the-spec
  (testing "a real binary file whose header text begins with 'solid'"
    (let [m (stl/parse-file (io/file "test/fixtures/triangle-binary-solid-header.stl"))]
      (is (= 1 (:triangle-count m))
          "detection keys on size == 84 + 50n, never on the leading text")
      (is (= [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0 9.0] (vec ^floats (:positions m)))
          "distinctive coordinates: a byte-order or offset error cannot look plausible")
      (is (= [1.0 2.0 3.0] (mapv float (:bbox-min m))))
      (is (= [7.0 8.0 9.0] (mapv float (:bbox-max m)))))))

(deftest ascii-fixture-header-is-an-absurd-triangle-count
  (testing "the property that kills a header-trusting parser"
    (let [f    (io/file "test/fixtures/cube-ascii-crlf.stl")
          size (.length f)
          buf  (doto (java.nio.ByteBuffer/wrap (java.nio.file.Files/readAllBytes (.toPath f)))
                 (.order java.nio.ByteOrder/LITTLE_ENDIAN))
          n    (stl/binary-triangle-count buf size)]
      (is (= 540028976 n)
          "bytes 80-83 of this ASCII text misread as half a billion triangles")
      (is (> (stl/expected-size n) (* 25 1024 1024 1024))
          "which would demand over 25 GB - the same class of lie as the real library's ASCII file")
      (is (not= size (stl/expected-size n)))
      (testing "and it parses correctly anyway"
        (let [m (stl/parse-file f)]
          (is (= 12 (:triangle-count m))))))))
