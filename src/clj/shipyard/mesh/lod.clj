(ns shipyard.mesh.lod
  "LOD tier generation via meshoptimizer (TECHNICAL.md §6.3, §6.6).

  Each tier is self-contained and compacted: simplification leaves output
  indices pointing into the *original* vertex array, so without a remap every
  tier would ship the full vertex buffer. Compacted, the 5% tier carries about
  a tenth of the vertices.

  **Order matters: weld -> simplify -> crease-split, not the reverse.** Issue #6
  concluded from a synthetic greebled plate that splitting first was fine, its
  floor sitting at 3.05%. Real hulls disagree. The Cruiser Hull expands 2.12x
  under crease splitting (66,086 -> 139,949 vertices), and on that topology
  simplification floors at **34.1%** and will not move at any error budget - so
  a 25% tier misses and a 5% tier is unreachable. Simplifying the
  position-welded mesh first reaches 24.98% and 4.93% exactly. Each tier is
  then crease-split on its own geometry.

  Interop rules that cost the spike real time (issue #6):
  - `MemoryStack` is 64 KB and thread-local, so it holds only small out-params
    like `result_error`. Mesh-sized buffers come from `MemoryUtil/memAlloc*`
    and are freed on every exit path by `with-native`.
  - `meshopt_simplify*` returns a count and never throws on ordinary failure.
  - `meshopt_SimplifyPrune` is never enabled: at `target_error` 1.0 it returns
    zero indices - the entire mesh deleted - and reports that as success.
  - Positions and attributes are passed as separate tight buffers, which avoids
    the tail-padding requirement that interleaved attribute views carry."
  (:require [clojure.tools.logging :as log]
            [shipyard.mesh.weld :as weld])
  (:import [java.nio ByteBuffer FloatBuffer IntBuffer]
           [org.lwjgl.system MemoryStack MemoryUtil]
           [org.lwjgl.util.meshoptimizer MeshOptimizer]))

(def ^:const float-bytes 4)
(def ^:const vec3-bytes 12)

(defmacro with-native
  "Like `with-open`, but for LWJGL native buffers: frees in reverse order on
  every exit path, including exceptions. `MemoryUtil/memFree` tolerates nil."
  [bindings & body]
  (let [pairs (partition 2 bindings)]
    (reduce (fn [inner [sym init]]
              `(let [~sym ~init]
                 (try ~inner (finally (MemoryUtil/memFree ~sym)))))
            `(do ~@body)
            (reverse pairs))))

(defn- float-buf ^FloatBuffer [^floats a]
  (doto (MemoryUtil/memAllocFloat (alength a)) (.put a) (.flip)))

(defn- int-buf ^IntBuffer [^ints a]
  (doto (MemoryUtil/memAllocInt (alength a)) (.put a) (.flip)))

(defn- bytes-view ^ByteBuffer [^FloatBuffer fb ^long floats]
  ;; memByteBuffer over the address, not .asFloatBuffer/.slice - those reset
  ;; byte order to big-endian and silently corrupt every float read.
  (MemoryUtil/memByteBuffer (MemoryUtil/memAddress fb) (int (* floats float-bytes))))

(defn- read-floats ^floats [^FloatBuffer fb ^long n]
  (let [a (float-array n)] (.get (.duplicate fb) a 0 (int n)) a))

(defn- read-ints ^ints [^IntBuffer ib ^long n]
  (let [a (int-array n)] (.get (.duplicate ib) a 0 (int n)) a))

(defn- compact
  "Remap a tier onto only the vertices it actually uses.

  Returns positions, normals and indices as Java arrays, so no native memory
  escapes this function."
  ;; No primitive hints on the counts: Clojure caps primitive-hinted fns at four
  ;; arguments (issue #6), and this needs five. Coerced at the call sites below.
  [^IntBuffer idx index-count ^FloatBuffer pos ^FloatBuffer nrm vertex-count]
  (with-native [remap (MemoryUtil/memAllocInt (int vertex-count))]
    (let [uniq (MeshOptimizer/meshopt_optimizeVertexFetchRemap remap idx)]
      (with-native [ridx (MemoryUtil/memAllocInt (int index-count))
                    rpos (MemoryUtil/memAllocFloat (int (* 3 uniq)))
                    rnrm (MemoryUtil/memAllocFloat (int (* 3 uniq)))]
        (MeshOptimizer/meshopt_remapIndexBuffer ridx idx (long index-count) remap)
        (MeshOptimizer/meshopt_remapVertexBuffer
         (bytes-view rpos (* 3 uniq)) (bytes-view pos (* 3 vertex-count))
         (long vertex-count) vec3-bytes remap)
        (MeshOptimizer/meshopt_remapVertexBuffer
         (bytes-view rnrm (* 3 uniq)) (bytes-view nrm (* 3 vertex-count))
         (long vertex-count) vec3-bytes remap)
        {:positions    (read-floats rpos (* 3 uniq))
         :normals      (read-floats rnrm (* 3 uniq))
         :indices      (read-ints ridx (long index-count))
         :vertex-count uniq
         :index-count  index-count}))))

(defn- smooth-normals
  "Area-weighted normals per position-welded vertex, with no crease splitting.

  These feed `simplifyWithAttributes` so shading still restrains collapses,
  without splitting the vertex buffer and locking the topology."
  ^floats [^floats pos ^ints idx ^long tris ^long verts]
  (let [{:keys [^floats normals ^doubles areas]} (weld/face-geometry pos idx tris)
        acc (double-array (* 3 verts))
        out (float-array (* 3 verts))]
    (dotimes [f tris]
      (let [a (aget areas f)]
        (dotimes [c 3]
          (let [v (aget idx (+ (* 3 f) c))]
            (dotimes [k 3]
              (aset acc (+ (* 3 v) k)
                    (+ (aget acc (+ (* 3 v) k)) (* a (aget normals (+ (* 3 f) k))))))))))
    (dotimes [v verts]
      (let [x (aget acc (* 3 v)), y (aget acc (+ (* 3 v) 1)), z (aget acc (+ (* 3 v) 2))
            len (Math/sqrt (+ (* x x) (* y y) (* z z)))]
        (when (pos? len)
          (aset out (* 3 v) (float (/ x len)))
          (aset out (+ (* 3 v) 1) (float (/ y len)))
          (aset out (+ (* 3 v) 2) (float (/ z len))))))
    out))

(defn generate
  "Parsed STL soup -> a vector of self-contained LOD tiers, densest first.

  `:tiers` are fractions of the original index count. Measured floors on real
  hulls sit near 3% once simplification runs on welded topology, so the 5% tier
  is safe; do not go below ~4%."
  ([mesh] (generate mesh {}))
  ([{:keys [^floats positions ^long triangle-count]}
    {:keys [tiers crease-deg attribute-weight target-error aggressive-below]
     :or   {tiers [1.0 0.25 0.05] crease-deg 35 attribute-weight 0.5
            target-error 0.01 aggressive-below 0.1}}]
   (let [welded (weld/weld-positions positions (* 3 triangle-count))
         V      (long (:vertex-count welded))
         ^floats wpos (:positions welded)
         ^ints  widx (:indices welded)
         total  (alength widx)
         wnrm   (smooth-normals wpos widx triangle-count V)]
     (with-native [pos (float-buf wpos)
                   nrm (float-buf wnrm)
                   src (int-buf widx)
                   wts (float-buf (float-array 3 (float attribute-weight)))
                   zero-wts (float-buf (float-array 3 (float 0.0)))
                   work (MemoryUtil/memAllocInt total)]
       (mapv
        (fn [ratio]
          (let [target (* 3 (long (Math/round (double (/ (* ratio total) 3)))))
                n (if (>= (double ratio) 1.0)
                    total
                    (with-open [stack (MemoryStack/stackPush)]
                      (let [err (.mallocFloat stack 1)]
                        (.limit work total) (.position work 0)
                        ;; The attribute term protects shading by refusing
                        ;; collapses that distort normals, which costs
                        ;; reduction: on the Cruiser Hull a 5% target stops at
                        ;; 11.8% with weight 0.5 and reaches 4.9% without. Below
                        ;; `aggressive-below` the tier is a distant placeholder,
                        ;; so size wins over shading fidelity.
                        (MeshOptimizer/meshopt_simplifyWithAttributes
                         work src pos V vec3-bytes nrm vec3-bytes
                         (if (< (double ratio) (double aggressive-below)) zero-wts wts) nil
                         target (float target-error) 0 err))))
                idx (if (>= (double ratio) 1.0)
                      widx
                      (do (.limit work (int n)) (.position work 0) (read-ints work n)))
                tris (quot n 3)
                ;; crease-split THIS tier on its own geometry - normals must
                ;; describe the decimated surface, not the original
                geom  (weld/face-geometry wpos idx tris)
                split (weld/crease-split {:positions wpos :indices idx} geom tris (double crease-deg))]
            (with-native [sp (float-buf (:positions split))
                          sn (float-buf (:normals split))
                          si (int-buf (:indices split))
                          sc (MemoryUtil/memAllocInt (alength ^ints (:indices split)))]
              (MeshOptimizer/meshopt_optimizeVertexCache sc si (long (:vertex-count split)))
              (let [tier (compact sc (alength ^ints (:indices split)) sp sn (:vertex-count split))]
                (log/debugf "lod tier %.2f: %d -> %d indices, %d verts"
                            (double ratio) total n (:vertex-count tier))
                (assoc tier :ratio (double ratio) :simplified-index-count n)))))
        tiers)))))
