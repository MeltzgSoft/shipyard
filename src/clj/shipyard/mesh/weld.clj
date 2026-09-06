(ns shipyard.mesh.weld
  "Turn STL triangle soup into an indexed mesh with correct hard edges.

  Two stages (TECHNICAL.md §6.2):

  1. **Weld** coincident positions on exact float bits. Measured on real hulls
     this reaches V/T = 0.497 - the closed-manifold ideal - and quantized
     snapping to a 1e-4 mm grid produces identical counts, so no snap fallback
     exists (issue #4).

  2. **Crease-split** the welded vertices again at hard edges: cluster each
     vertex's incident faces into smoothing groups whose neighbours agree within
     the crease angle, and emit one output vertex per (position, group) with an
     area-weighted normal. Welding alone rounds off every panel edge on these
     hulls; splitting alone shares nothing.

  The weld is load-bearing for simplification, not just memory: a mesh whose
  coincident positions differ by a single ULP tears under decimation - 6,684
  boundary edges against 99 (issue #6). That surfaces two stages downstream
  looking like a simplifier bug, which is why `check-ratio` exists."
  (:require [clojure.tools.logging :as log]
            [shipyard.mesh.float :as mesh-float]))

;; ---------------------------------------------------------------------------
;; stage 1 - weld on exact float bits
;; ---------------------------------------------------------------------------

(defn- capacity-for ^long [^long corners]
  (loop [c 16] (if (>= c (* 2 corners)) c (recur (* 2 c)))))

(defn weld-positions
  "Dedupe the `corners` xyz triples in `pos` (length 3*corners).

  Open-addressed table over primitive arrays rather than a HashMap of boxed
  keys: three ints do not pack into a long, so a map key would allocate one
  object per corner - 400k allocations for a single cruiser hull."
  [^floats pos ^long corners]
  (let [cap   (capacity-for corners)
        mask  (dec cap)
        kx    (int-array cap), ky (int-array cap), kz (int-array cap)
        slot  (int-array cap -1)
        out   (float-array (* 3 corners))
        index (int-array corners)]
    (loop [i 0, n 0]
      (if (= i corners)
        {:positions    (java.util.Arrays/copyOf out (int (* 3 n)))
         :indices      index
         :vertex-count n}
        (let [o  (* 3 i)
              x  (aget pos o), y (aget pos (+ o 1)), z (aget pos (+ o 2))
              bx (int (mesh-float/canonical-bits x))
              by (int (mesh-float/canonical-bits y))
              bz (int (mesh-float/canonical-bits z))
              ;; classic spatial hash; the three primes decorrelate axis-aligned
              ;; geometry, which is most of this library
              h  (bit-xor (unchecked-multiply-int bx 73856093)
                          (unchecked-multiply-int by 19349663)
                          (unchecked-multiply-int bz 83492791))
              ;; probe returns the vertex id for this position; it equals n
              ;; exactly when the position is new, since ids are handed out in
              ;; order and every existing id is < n
              id (loop [p (bit-and h mask)]
                   (let [s (aget slot p)]
                     (cond
                       (= s -1)
                       (do (aset slot p (int n))
                           (aset kx p bx) (aset ky p by) (aset kz p bz)
                           (aset out (int (* 3 n)) x)
                           (aset out (int (+ (* 3 n) 1)) y)
                           (aset out (int (+ (* 3 n) 2)) z)
                           n)

                       (and (= (aget kx p) bx) (= (aget ky p) by) (= (aget kz p) bz))
                       s

                       :else (recur (bit-and (inc p) mask)))))]
          (aset index i (int id))
          (recur (inc i) (if (= id n) (inc n) n)))))))

;; ---------------------------------------------------------------------------
;; face geometry
;; ---------------------------------------------------------------------------

(defn face-geometry
  "Per-face unit normals and areas, computed from welded geometry.

  Degenerate faces (zero area) get a zero normal and are excluded from crease
  decisions - real STLs contain them, and a NaN normal would poison every
  smoothing group it touched."
  [^floats pos ^ints idx ^long tris]
  (let [nrm (float-array (* 3 tris))
        area (double-array tris)]
    (dotimes [f tris]
      (let [a (* 3 (aget idx (* 3 f)))
            b (* 3 (aget idx (+ (* 3 f) 1)))
            c (* 3 (aget idx (+ (* 3 f) 2)))
            ux (- (aget pos b) (aget pos a))
            uy (- (aget pos (+ b 1)) (aget pos (+ a 1)))
            uz (- (aget pos (+ b 2)) (aget pos (+ a 2)))
            vx (- (aget pos c) (aget pos a))
            vy (- (aget pos (+ c 1)) (aget pos (+ a 1)))
            vz (- (aget pos (+ c 2)) (aget pos (+ a 2)))
            cx (- (* uy vz) (* uz vy))
            cy (- (* uz vx) (* ux vz))
            cz (- (* ux vy) (* uy vx))
            len (Math/sqrt (+ (* cx cx) (* cy cy) (* cz cz)))]
        (aset area f (/ len 2.0))
        (when (pos? len)
          (aset nrm (* 3 f) (float (/ cx len)))
          (aset nrm (+ (* 3 f) 1) (float (/ cy len)))
          (aset nrm (+ (* 3 f) 2) (float (/ cz len))))))
    {:normals nrm :areas area}))

;; ---------------------------------------------------------------------------
;; stage 2 - crease split
;; ---------------------------------------------------------------------------

(defn- uf-find ^long [^ints parent ^long x]
  (loop [r x]
    (let [p (aget parent (int r))]
      (if (= p (int r))
        (loop [c x]                                   ; path compression
          (let [n (aget parent (int c))]
            (if (= n (int r)) r (do (aset parent (int c) (int r)) (recur n)))))
        (recur p)))))

(defn- uf-union [^ints parent ^long a ^long b]
  (let [ra (uf-find parent a), rb (uf-find parent b)]
    (when-not (= ra rb) (aset parent (int rb) (int ra)))))

(defn- corner-of
  "Which of face `f`'s three corners carries welded vertex `v`."
  ^long [^ints idx ^long f ^long v]
  (let [b (* 3 f)]
    (cond (= (aget idx (int b)) (int v)) b
          (= (aget idx (int (+ b 1))) (int v)) (+ b 1)
          :else (+ b 2))))

(defn crease-split
  "Split welded vertices into smoothing groups, returning the final mesh.

  Two faces sharing an edge join the same group when their normals agree within
  `crease-deg`. Non-manifold edges (more than two faces) are left split: there
  is no meaningful shared normal, and guessing one produces visible artefacts."
  [{:keys [^floats positions ^ints indices]}
   {:keys [^floats normals ^doubles areas]}
   ^long tris ^double crease-deg]
  (let [corners (* 3 tris)
        cos-t   (Math/cos (Math/toRadians crease-deg))
        parent  (int-array corners)
        ;; long-keyed open-addressed edge table: key packs the two welded vertex
        ;; ids, value holds the first face that claimed the edge (-1 once a
        ;; second face has been paired, so a third is ignored)
        cap     (capacity-for corners)
        mask    (dec cap)
        ekey    (long-array cap Long/MIN_VALUE)
        eface   (int-array cap -1)]
    (dotimes [i corners] (aset parent i i))
    (dotimes [f tris]
      (dotimes [c 3]
        (let [u (aget indices (+ (* 3 f) c))
              v (aget indices (+ (* 3 f) (rem (inc c) 3)))
              lo (min u v), hi (max u v)
              k  (bit-or (bit-shift-left (long lo) 32) (long hi))
              ;; splitmix64's multiplier. Written signed because Clojure reads
              ;; 0x9E3779B97F4A7C15 as a BigInt - it exceeds Long/MAX_VALUE.
              h  (bit-and (Long/hashCode (unchecked-multiply k -7046029254386353131)) mask)]
          (loop [p h]
            (let [kk (aget ekey (int p))]
              (cond
                (= kk Long/MIN_VALUE)
                (do (aset ekey (int p) k) (aset eface (int p) (int f)))

                (= kk k)
                (let [g (aget eface (int p))]
                  (when-not (= g -1)
                    (aset eface (int p) -1)              ; edge is now used up
                    (let [d (+ (* (aget normals (* 3 f)) (aget normals (* 3 g)))
                               (* (aget normals (+ (* 3 f) 1)) (aget normals (+ (* 3 g) 1)))
                               (* (aget normals (+ (* 3 f) 2)) (aget normals (+ (* 3 g) 2))))]
                      (when (and (pos? (aget areas f)) (pos? (aget areas g)) (>= d cos-t))
                        (uf-union parent (corner-of indices f u) (corner-of indices g u))
                        (uf-union parent (corner-of indices f v) (corner-of indices g v))))))

                :else (recur (bit-and (inc p) mask))))))))
    ;; roots -> output vertices
    (let [out-id (int-array corners -1)
          acc    (double-array (* 3 corners))
          idx    (int-array corners)]
      (loop [c 0, v 0]
        (if (= c corners)
          (let [outp (float-array (* 3 v))
                outn (float-array (* 3 v))]
            (dotimes [c corners]
              (let [o (aget idx c)
                    s (* 3 (aget indices c))]
                (aset outp (* 3 o) (aget positions s))
                (aset outp (+ (* 3 o) 1) (aget positions (+ s 1)))
                (aset outp (+ (* 3 o) 2) (aget positions (+ s 2)))))
            (dotimes [o v]
              (let [x (aget acc (* 3 o)), y (aget acc (+ (* 3 o) 1)), z (aget acc (+ (* 3 o) 2))
                    len (Math/sqrt (+ (* x x) (* y y) (* z z)))]
                (when (pos? len)
                  (aset outn (* 3 o) (float (/ x len)))
                  (aset outn (+ (* 3 o) 1) (float (/ y len)))
                  (aset outn (+ (* 3 o) 2) (float (/ z len))))))
            {:positions outp :normals outn :indices idx
             :vertex-count v :triangle-count tris})
          (let [r (uf-find parent c)
                existing (aget out-id (int r))
                o (if (= existing -1) v existing)]
            (when (= existing -1) (aset out-id (int r) (int o)))
            (aset idx c (int o))
            (let [f (quot c 3), a (aget areas f)]           ; area-weighted accumulation
              (aset acc (* 3 o) (+ (aget acc (* 3 o)) (* a (aget normals (* 3 f)))))
              (aset acc (+ (* 3 o) 1) (+ (aget acc (+ (* 3 o) 1)) (* a (aget normals (+ (* 3 f) 1)))))
              (aset acc (+ (* 3 o) 2) (+ (aget acc (+ (* 3 o) 2)) (* a (aget normals (+ (* 3 f) 2))))))
            (recur (inc c) (if (= existing -1) (inc v) v))))))))

;; ---------------------------------------------------------------------------
;; entry point
;; ---------------------------------------------------------------------------

(def ^:const warn-ratio 1.25)
(def ^:const fail-ratio 2.5)

(def ^:const min-triangles-for-ratio
  "Below this the ratio says nothing. It is a statistical property of a surface,
  and on a handful of triangles boundary effects dominate: three mutually
  perpendicular triangles legitimately reach 3.0, which is also what unwelded
  soup looks like. Real parts run from ~1,200 triangles up."
  100)

(defn check-ratio
  "Guard that welding actually happened.

  Unwelded STL is V/T = 3.0; a closed manifold with no creases welds to 0.5.
  Measured on real hulls at 35 degrees the range is 0.70-1.07, so the warn line
  sits at 1.25 - not 1.0, which fires on two of four reference parts in normal
  operation (issue #4)."
  [^long verts ^long tris label]
  (let [r (double (/ verts (max tris 1)))]
    (when (< tris min-triangles-for-ratio)
      (log/debugf "skipping vertex ratio check for %s: only %d triangles" label tris))
    (when (and (>= tris min-triangles-for-ratio) (>= r fail-ratio))
      (throw (ex-info (format "welding did not take effect: V/T = %.3f (>= %.2f). Unwelded soup is 3.0."
                              r fail-ratio)
                      {:ratio r :vertices verts :triangles tris :label label})))
    (when (and (>= tris min-triangles-for-ratio) (> r warn-ratio))
      (log/warnf "high vertex ratio for %s: V/T = %.3f (> %.2f)" label r warn-ratio))
    r))

(defn weld
  "Parsed STL soup -> indexed mesh with crease-split normals."
  ([mesh] (weld mesh {}))
  ([{:keys [^floats positions ^long triangle-count] :as mesh}
    {:keys [crease-deg label] :or {crease-deg 35 label "mesh"}}]
   (let [welded (weld-positions positions (* 3 triangle-count))
         geom   (face-geometry (:positions welded) (:indices welded) triangle-count)
         out    (crease-split welded geom triangle-count (double crease-deg))]
     (check-ratio (:vertex-count out) triangle-count label)
     (assoc out
            :welded-vertex-count (:vertex-count welded)
            :bbox-min (:bbox-min mesh)
            :bbox-max (:bbox-max mesh)))))
