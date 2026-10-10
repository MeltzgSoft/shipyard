(ns shipyard.paint.projection
  "Versioned ordinal appearance resources: small tables followed by typed masks."
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

(def magic 0x53595250)
(def version 1)
(def header-size 16)

(defn aligned-size [size] (* 4 (quot (+ size 3) 4)))

(defn- valid-indices? [^ints values size]
  (loop [index 0]
    (if (= index #?(:clj (alength ^ints values) :cljs (.-length values))) true
        (if (<= 0 (aget values index) (dec size)) (recur (inc index)) false))))

#?(:clj
   (do
     (defn- byte-count [bytes] (alength ^bytes bytes))
     (defn- wrap [bytes] (doto (java.nio.ByteBuffer/wrap bytes) (.order java.nio.ByteOrder/LITTLE_ENDIAN)))
     (defn- u32 [^java.nio.ByteBuffer view offset] (Integer/toUnsignedLong (.getInt view offset)))
     (defn- metadata-bytes [metadata] (.getBytes (pr-str metadata) java.nio.charset.StandardCharsets/UTF_8))
     (defn- text [bytes offset count] (String. ^bytes bytes (int offset) (int count) java.nio.charset.StandardCharsets/UTF_8))
     (defn- indices [view offset count] (int-array (map #(u32 view (+ offset (* 4 %))) (range count)))))
   :cljs
   (do
     (defn- byte-count [bytes] (.-byteLength bytes))
     (defn- wrap [bytes] (js/DataView. bytes))
     (defn- u32 [^js view offset] (.getUint32 view offset true))
     (defn- metadata-bytes [metadata] (.encode (js/TextEncoder.) (pr-str metadata)))
     (defn- text [bytes offset count] (.decode (js/TextDecoder. "utf-8" #js {:fatal true}) (js/Uint8Array. bytes offset count)))
     (defn- indices [^js view offset count] (js/Uint32Array. (.-buffer view) offset count))))

(defn encode
  "Metadata contains source identity/revisions and layer/detail tables. Arrays use
  tier-0 triangle ordinals, with 0 meaning Primary/no detail. No coordinate keys."
  [metadata layers details]
  (let [triangle-count (count layers)
        _ (when (not= triangle-count (count details)) (throw (ex-info "Projection mask lengths differ" {})))
        table (metadata-bytes metadata)
        size #?(:clj (alength ^bytes table) :cljs (.-length table))
        payload (+ header-size (aligned-size size))
        total (+ payload (* 8 triangle-count))
        buffer #?(:clj (byte-array total) :cljs (js/ArrayBuffer. total))
        view (wrap buffer)
        put! #?(:clj (fn [offset value] (.putInt ^java.nio.ByteBuffer view (int offset) (unchecked-int value)))
                :cljs (fn [offset value] (.setUint32 view offset value true)))]
    (doseq [[offset value] [[0 magic] [4 version] [8 triangle-count] [12 size]]] (put! offset value))
    #?(:clj (System/arraycopy table 0 buffer header-size size)
       :cljs (.set (js/Uint8Array. buffer header-size size) table))
    (doseq [index (range triangle-count)]
      (put! (+ payload (* 4 index)) (nth layers index))
      (put! (+ payload (* 4 (+ triangle-count index))) (nth details index)))
    buffer))

(defn decode
  "Validate layout and table bounds before exposing immutable typed-array views."
  [bytes]
  (when (< (byte-count bytes) header-size) (throw (ex-info "Truncated projection header" {})))
  (let [view (wrap bytes)
        triangle-count (u32 view 8) size (u32 view 12)
        payload (+ header-size (aligned-size size))]
    (when (or (not= magic (u32 view 0)) (not= version (u32 view 4)))
      (throw (ex-info "Unknown projection format" {})))
    (when (not= (byte-count bytes) (+ payload (* 8 triangle-count)))
      (throw (ex-info "Invalid projection length" {})))
    (let [metadata (edn/read-string (text bytes header-size size))
          layers (indices view payload triangle-count)
          details (indices view (+ payload (* 4 triangle-count)) triangle-count)]
      (when (or (not (map? metadata))
                (not (string? (:mesh-key metadata)))
                (not (string? (:region-revision metadata)))
                (not (vector? (:layer-table metadata)))
                (not= "Primary" (first (:layer-table metadata)))
                (not (vector? (:detail-table metadata)))
                (empty? (:detail-table metadata))
                (some? (first (:detail-table metadata)))
                (not (valid-indices? layers (count (:layer-table metadata))))
                (not (valid-indices? details (count (:detail-table metadata)))))
        (throw (ex-info "Invalid projection tables or mask indices" {})))
      (assoc metadata :triangle-count triangle-count :triangle-layers layers :triangle-details details))))
