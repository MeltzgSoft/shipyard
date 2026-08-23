(ns shipyard.wire
  "The .symesh wire format - one definition, both runtimes.

  Encoder and decoder live together deliberately (TECHNICAL.md §6.4). Hand-
  mirroring magic bytes and field offsets across a JVM encoder and a JS decoder
  is a drift bug that corrupts geometry silently rather than throwing, and the
  cross-runtime roundtrip test is what proves the two agree.

  One self-contained file per LOD tier: `mesh/<sha256>.<tier>.symesh`. A viewer
  holds exactly one tier, and compacting each tier separately shrinks the 5%
  tier to roughly a tenth of tier 0.

  Little-endian throughout, every field 4-byte aligned.

      offset  type          field
      ------------------------------------------------
       0      char[8]       magic \"SYMESH\\0\\0\"
       8      uint32        version
      12      uint32        flags          bit0 = normals present
      16      uint32        vertexCount  V
      20      uint32        indexCount   I
      24      float32[3]    bboxMin
      36      float32[3]    bboxMax
      ------------------------------------------------
      48      float32[3V]   positions
              float32[3V]   normals        (when flags bit0)
              uint32[I]     indices")

;; --- layout: the single source of truth ------------------------------------

(def magic-bytes [83 89 77 69 83 72 0 0])          ; "SYMESH\0\0"
(def ^:const version 1)
(def ^:const flag-normals 1)

(def ^:const off-magic 0)
(def ^:const off-version 8)
(def ^:const off-flags 12)
(def ^:const off-vertex-count 16)
(def ^:const off-index-count 20)
(def ^:const off-bbox-min 24)
(def ^:const off-bbox-max 36)
(def ^:const off-payload 48)

(def ^:const float-bytes 4)
(def ^:const uint-bytes 4)

(defn byte-size
  "Total encoded size for `v` vertices and `i` indices."
  [v i normals?]
  (+ off-payload
     (* 3 v float-bytes)
     (if normals? (* 3 v float-bytes) 0)
     (* i uint-bytes)))

(defn normals-offset [_v] (+ off-payload 0))
(defn indices-offset [v normals?]
  (+ off-payload (* 3 v float-bytes) (if normals? (* 3 v float-bytes) 0)))

;; --- platform primitives ---------------------------------------------------

#?(:clj
   (do
     (defn- alloc [n]
       (doto (java.nio.ByteBuffer/allocate (int n))
         (.order java.nio.ByteOrder/LITTLE_ENDIAN)))
     (defn- wrap [^bytes b]
       (doto (java.nio.ByteBuffer/wrap b)
         (.order java.nio.ByteOrder/LITTLE_ENDIAN)))
     (defn- put-u8 [^java.nio.ByteBuffer b off v] (.put b (int off) (unchecked-byte v)))
     (defn- get-u8 [^java.nio.ByteBuffer b off] (bit-and (.get b (int off)) 0xFF))
     (defn- put-u32 [^java.nio.ByteBuffer b off v] (.putInt b (int off) (unchecked-int v)))
     (defn- get-u32 [^java.nio.ByteBuffer b off] (bit-and (.getInt b (int off)) 0xFFFFFFFF))
     (defn- put-f32 [^java.nio.ByteBuffer b off v] (.putFloat b (int off) (float v)))
     (defn- get-f32 [^java.nio.ByteBuffer b off] (.getFloat b (int off)))
     (defn- put-floats [^java.nio.ByteBuffer b off ^floats a]
       (dotimes [i (alength a)] (.putFloat b (int (+ off (* i float-bytes))) (aget a i))))
     (defn- put-ints [^java.nio.ByteBuffer b off ^ints a]
       (dotimes [i (alength a)] (.putInt b (int (+ off (* i uint-bytes))) (aget a i))))

     (defn- get-floats [^java.nio.ByteBuffer b off n]
       (let [a (float-array n)]
         (dotimes [i n] (aset a i (.getFloat b (int (+ off (* i float-bytes))))))
         a))
     (defn- get-ints [^java.nio.ByteBuffer b off n]
       (let [a (int-array n)]
         (dotimes [i n] (aset a i (.getInt b (int (+ off (* i uint-bytes))))))
         a))
     (defn- finish [^java.nio.ByteBuffer b] (.array b))
     (defn- arr-len
       "Length of any primitive array without a type hint. `alength` on an
       untyped local reflects, and shared cljc code cannot carry ^floats."
       ^long [a] (java.lang.reflect.Array/getLength a)))

   :cljs
   (do
     (defn- alloc [n] (js/DataView. (js/ArrayBuffer. n)))
     (defn- wrap [b] (js/DataView. (if (instance? js/ArrayBuffer b) b (.-buffer b))))
     (defn- put-u8 [^js b off v] (.setUint8 b off v))
     (defn- get-u8 [^js b off] (.getUint8 b off))
     (defn- put-u32 [^js b off v] (.setUint32 b off v true))
     (defn- get-u32 [^js b off] (.getUint32 b off true))
     (defn- put-f32 [^js b off v] (.setFloat32 b off v true))
     (defn- get-f32 [^js b off] (.getFloat32 b off true))
     (defn- put-floats [^js b off a]
       (.set (js/Float32Array. (.-buffer b) off (alength a)) a))
     (defn- put-ints [^js b off a]
       (.set (js/Uint32Array. (.-buffer b) off (alength a)) a))
     (defn- get-floats [^js b off n]
       ;; A view, not a copy - this is the whole point of the format: geometry
       ;; reaches the GPU without per-vertex parsing.
       (js/Float32Array. (.-buffer b) off n))
     (defn- get-ints [^js b off n]
       (js/Uint32Array. (.-buffer b) off n))
     (defn- finish [^js b] (.-buffer b))
     (defn- arr-len [a] (alength a))))

;; --- encode / decode -------------------------------------------------------

(defn encode
  "Mesh -> encoded bytes (`byte[]` on the JVM, `ArrayBuffer` in the browser)."
  [{:keys [positions normals indices vertex-count bbox-min bbox-max]}]
  (let [v        (or vertex-count (quot (arr-len positions) 3))
        i        (arr-len indices)
        normals? (boolean normals)
        buf      (alloc (byte-size v i normals?))]
    (dotimes [k 8] (put-u8 buf (+ off-magic k) (nth magic-bytes k)))
    (put-u32 buf off-version version)
    (put-u32 buf off-flags (if normals? flag-normals 0))
    (put-u32 buf off-vertex-count v)
    (put-u32 buf off-index-count i)
    (dotimes [k 3]
      (put-f32 buf (+ off-bbox-min (* k float-bytes)) (nth bbox-min k 0.0))
      (put-f32 buf (+ off-bbox-max (* k float-bytes)) (nth bbox-max k 0.0)))
    (put-floats buf off-payload positions)
    (when normals? (put-floats buf (+ off-payload (* 3 v float-bytes)) normals))
    (put-ints buf (indices-offset v normals?) indices)
    (finish buf)))

(defn decode
  "Encoded bytes -> mesh. In the browser the arrays are typed-array views over
  the received buffer, so nothing is copied or parsed per vertex."
  [bytes]
  (let [buf (wrap bytes)]
    (dotimes [k 8]
      (when-not (= (get-u8 buf (+ off-magic k)) (nth magic-bytes k))
        (throw (ex-info "not a .symesh file: bad magic" {:offset k}))))
    (let [ver (get-u32 buf off-version)]
      (when-not (= ver version)
        (throw (ex-info (str "unsupported .symesh version " ver ", expected " version)
                        {:found ver :expected version})))
      (let [flags    (get-u32 buf off-flags)
            normals? (pos? (bit-and flags flag-normals))
            v        (get-u32 buf off-vertex-count)
            i        (get-u32 buf off-index-count)]
        {:vertex-count v
         :index-count  i
         :positions    (get-floats buf off-payload (* 3 v))
         :normals      (when normals?
                         (get-floats buf (+ off-payload (* 3 v float-bytes)) (* 3 v)))
         :indices      (get-ints buf (indices-offset v normals?) i)
         ;; read from the header so the camera can frame a part without
         ;; scanning a million vertices
         :bbox-min     (mapv #(get-f32 buf (+ off-bbox-min (* % float-bytes))) (range 3))
         :bbox-max     (mapv #(get-f32 buf (+ off-bbox-max (* % float-bytes))) (range 3))}))))
