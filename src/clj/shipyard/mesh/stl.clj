(ns shipyard.mesh.stl
  "Binary STL parsing, with an ASCII fallback.

  Output is triangle soup in flat primitive arrays: `:positions` holds 9 floats
  per triangle (three corners, xyz each) in file order. Welding and normal
  generation happen downstream (TECHNICAL.md §6.2).

  Stored face normals are deliberately NOT returned. They are frequently zero,
  or inconsistent with vertex winding, in files that otherwise print fine - and
  §6.2 recomputes area-weighted normals from geometry regardless, so reading
  them would cost memory to produce a value nothing trusts."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels FileChannel]
           [java.nio.file StandardOpenOption]))

(def ^:const header-bytes 84)   ; 80-byte header + uint32 triangle count
(def ^:const record-bytes 50)   ; 12 normal + 36 vertices + 2 attribute bytes
(def ^:const normal-bytes 12)

(defn expected-size
  "Byte length a binary STL with `n` triangles must have, exactly."
  ^long [^long n]
  (+ header-bytes (* record-bytes n)))

(defn binary-triangle-count
  "The triangle count a binary STL header claims, or nil if `size` is too small
  to hold a header at all.

  Never trust this without checking it against the real file size: the one
  ASCII file in this library declares 1,814,065,765 triangles, which would ask
  for ~90 GB."
  [^ByteBuffer buf ^long size]
  (when (>= size header-bytes)
    (bit-and (.getInt buf 80) 0xFFFFFFFF)))

(defn- read-binary
  "Parse `n` triangles out of an already-validated little-endian buffer.

  One flat loop over all 3n corners rather than nested loops: absolute gets mean
  nothing mutates buffer position, nothing is allocated per triangle, and there
  is no inner `recur` to mis-target."
  [^ByteBuffer buf ^long n]
  (let [corners (* 3 n)
        pos     (float-array (* 9 n))]
    ;; Accumulate the bbox in doubles: Clojure has no primitive float local, so
    ;; float accumulators box and make Math/min ambiguous.
    (loop [v 0
           mnx Double/POSITIVE_INFINITY mny Double/POSITIVE_INFINITY mnz Double/POSITIVE_INFINITY
           mxx Double/NEGATIVE_INFINITY mxy Double/NEGATIVE_INFINITY mxz Double/NEGATIVE_INFINITY]
      (if (= v corners)
        {:triangle-count n
         :positions      pos
         :bbox-min       [(float mnx) (float mny) (float mnz)]
         :bbox-max       [(float mxx) (float mxy) (float mxz)]}
        (let [tri (quot v 3)
              cor (rem v 3)
              o   (+ header-bytes (* record-bytes tri) normal-bytes (* cor 12))
              x   (.getFloat buf o)
              y   (.getFloat buf (+ o 4))
              z   (.getFloat buf (+ o 8))
              d   (* v 3)]
          (aset pos d x)
          (aset pos (+ d 1) y)
          (aset pos (+ d 2) z)
          (recur (inc v)
                 (Math/min mnx (double x)) (Math/min mny (double y)) (Math/min mnz (double z))
                 (Math/max mxx (double x)) (Math/max mxy (double y)) (Math/max mxz (double z))))))))

(defn- read-ascii
  "Parse an ASCII STL by scanning for `vertex` lines.

  Deliberately tolerant of layout: solid/facet/loop structure is ignored and
  only vertex records are collected, because the malformed-binary path lands
  here too and rigid grammar checking would turn a recoverable file into a
  failure."
  [rdr source-size declared]
  (let [verts (java.util.ArrayList.)]
    (with-open [r (io/reader rdr)]
      (doseq [line (line-seq r)]
        (let [l (str/trim line)]
          (when (str/starts-with? l "vertex")
            (let [parts (str/split l #"\s+")]
              (when (= 4 (count parts))
                (.add verts (Float/parseFloat (nth parts 1)))
                (.add verts (Float/parseFloat (nth parts 2)))
                (.add verts (Float/parseFloat (nth parts 3)))))))))
    (let [total (.size verts)
          n     (quot total 9)]
      (when (or (zero? total) (pos? (rem total 9)))
        (throw (ex-info (str "Not a usable STL: the file is not valid binary "
                             "(size " source-size " bytes, header declares "
                             declared " triangles, which needs "
                             (expected-size (or declared 0)) ") and ASCII parsing "
                             "found " (quot total 3) " vertices, which is not a "
                             "whole number of triangles. The file is most likely "
                             "truncated or corrupt.")
                        {:size source-size :declared declared :vertices (quot total 3)})))
      (let [pos (float-array total)]
        (loop [i 0
               mnx Double/POSITIVE_INFINITY mny Double/POSITIVE_INFINITY mnz Double/POSITIVE_INFINITY
               mxx Double/NEGATIVE_INFINITY mxy Double/NEGATIVE_INFINITY mxz Double/NEGATIVE_INFINITY]
          (if (= i total)
            {:triangle-count n
             :positions      pos
             :bbox-min       [(float mnx) (float mny) (float mnz)]
             :bbox-max       [(float mxx) (float mxy) (float mxz)]}
            (let [x (float (.get verts i))
                  y (float (.get verts (+ i 1)))
                  z (float (.get verts (+ i 2)))]
              (aset pos i x)
              (aset pos (+ i 1) y)
              (aset pos (+ i 2) z)
              (recur (+ i 3)
                     (Math/min mnx (double x)) (Math/min mny (double y)) (Math/min mnz (double z))
                     (Math/max mxx (double x)) (Math/max mxy (double y)) (Math/max mxz (double z))))))))))

(defn parse-buffer
  "Parse a little-endian buffer of `size` bytes, falling back to ASCII when the
  binary layout does not check out. `ascii-source` is anything `io/reader`
  accepts, used only on the fallback path."
  [^ByteBuffer buf ^long size ascii-source]
  (let [declared (binary-triangle-count buf size)]
    (if (and declared (= size (expected-size declared)))
      (read-binary buf declared)
      (read-ascii ascii-source size declared))))

(defn parse-bytes
  "Parse an STL held in memory. Used by unit tests, which must not touch disk."
  [^bytes b]
  (let [buf (doto (ByteBuffer/wrap b) (.order ByteOrder/LITTLE_ENDIAN))]
    (parse-buffer buf (alength b) (java.io.ByteArrayInputStream. b))))

(defn parse-file
  "Parse an STL from disk, memory-mapped so the largest hull in the library
  (57.8 MB) costs no heap.

  Note for anything that writes near the library: on Windows a mapping keeps the
  file locked until the buffer is collected, whatever the channel does. Harmless
  here because library files are only ever read, but worth remembering if the
  cache (#13) ever writes back."
  [path]
  (let [f    (io/file path)
        size (.length ^File f)]
    (with-open [ch (FileChannel/open (.toPath f)
                                     (into-array StandardOpenOption [StandardOpenOption/READ]))]
      (let [buf (doto (.map ch java.nio.channels.FileChannel$MapMode/READ_ONLY 0 size)
                  (.order ByteOrder/LITTLE_ENDIAN))]
        (parse-buffer buf size f)))))
