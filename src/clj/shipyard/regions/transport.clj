(ns shipyard.regions.transport
  "Versioned CBOR stroke boundary. RFC 8746 uint32 indices or a compact bitset."
  (:require [clojure.string :as str])
  (:import [com.fasterxml.jackson.core JsonToken]
           [com.fasterxml.jackson.dataformat.cbor CBORFactory CBORParser]
           [java.io InputStream]
           [java.nio ByteBuffer ByteOrder]))

(def content-type "application/cbor")
(def max-body-bytes (* 16 1024 1024))
(def metadata-fields #{"part-id" "mesh-key" "revision" "layer-revision" "action" "layer" "mode" "angle"})
(def schema
  [:map {:closed true}
   [:version [:= 1]]
   [:metadata [:map {:closed true}
               ["part-id" string?] ["mesh-key" [:re #"[0-9a-f]{64}"]]
               ["revision" [:and string? [:fn #(some? (parse-long %))]]]
               ["layer-revision" [:and string? [:fn #(some? (parse-long %))]]]
               ["action" [:= "assign"]] ["layer" string?]
               ["mode" [:enum "facets" "faces"]]
               ["angle" [:and string? [:fn #(when-let [n (parse-long %)] (<= 0 n 90))]]]]]
   [:triangle-count [:int {:min 1 :max Integer/MAX_VALUE}]]
   [:encoding [:enum "indices" "bitset"]]
   [:indices [:vector {:min 1} [:int {:min 0 :max 4294967295}]]]])

(defn- require! [valid?]
  (when-not valid? (throw (ex-info "Invalid CBOR region stroke" {:type ::invalid}))))

(defn selection-indices
  "Decode and validate the numeric selection without allocating by mesh size."
  [triangle-count encoding ^bytes payload]
  (require! (<= 1 triangle-count Integer/MAX_VALUE))
  (case encoding
    "indices"
    (do (require! (zero? (mod (alength payload) 4)))
        (let [buffer (doto (ByteBuffer/wrap payload) (.order ByteOrder/LITTLE_ENDIAN))]
          (loop [result (transient [])]
            (if (.hasRemaining buffer)
              (let [index (Integer/toUnsignedLong (.getInt buffer))]
                (require! (< index triangle-count))
                (recur (conj! result index)))
              (persistent! result)))))
    "bitset"
    (do (require! (= (alength payload) (quot (+ triangle-count 7) 8)))
        (persistent!
         (reduce (fn [result byte-index]
                   (loop [bits (bit-and 255 (aget payload byte-index)) result result]
                     (if (zero? bits) result
                         (let [index (+ (* byte-index 8) (Integer/numberOfTrailingZeros (int bits)))]
                           (require! (< index triangle-count))
                           (recur (bit-and bits (dec bits)) (conj! result index))))))
                 (transient []) (range (alength payload)))))
    (require! false)))

(defn decode
  "Read exactly [version, metadata, triangle-count, encoding, selection]."
  [^bytes bytes]
  (with-open [^CBORParser parser (.createParser (CBORFactory.) bytes)]
    (require! (= JsonToken/START_ARRAY (.nextToken parser)))
    (require! (= JsonToken/VALUE_NUMBER_INT (.nextToken parser)))
    (let [version (.getIntValue parser)
          _ (require! (= JsonToken/START_OBJECT (.nextToken parser)))
          metadata (loop [result {}]
                     (if (= JsonToken/END_OBJECT (.nextToken parser)) result
                         (let [key (.currentName parser)]
                           (require! (and (= JsonToken/FIELD_NAME (.currentToken parser))
                                          (metadata-fields key) (not (contains? result key))
                                          (= JsonToken/VALUE_STRING (.nextToken parser))))
                           (let [value (.getText parser)]
                             (require! (<= (count value) 4096))
                             (recur (assoc result key value))))))
          _ (require! (= JsonToken/VALUE_NUMBER_INT (.nextToken parser)))
          triangle-count (.getLongValue parser)
          _ (require! (= JsonToken/VALUE_STRING (.nextToken parser)))
          encoding (.getText parser)
          _ (require! (= JsonToken/VALUE_EMBEDDED_OBJECT (.nextToken parser)))
          _ (require! (= (if (= encoding "indices") 70 -1) (.getCurrentTag parser)))
          indices (selection-indices triangle-count encoding (.getBinaryValue parser))]
      (require! (and (= JsonToken/END_ARRAY (.nextToken parser)) (nil? (.nextToken parser))))
      {:version version :metadata metadata :triangle-count triangle-count :encoding encoding :indices indices})))

(defn wrap-body [handler]
  (fn [request]
    (if (and (= :post (:request-method request)) (= "/parts/regions/stroke" (:uri request)))
      (if-not (= content-type (some-> (get-in request [:headers "content-type"]) (str/split #";") (first) (str/trim)))
        {:status 415 :headers {"content-type" "text/plain"} :body "Region strokes require application/cbor."}
        (let [decoded (try
                        (let [bytes (.readNBytes ^InputStream (:body request) (inc max-body-bytes))]
                          (if (> (alength bytes) max-body-bytes) {:status 413}
                              {:body-params (decode bytes)}))
                        (catch Exception _ {:status 400}))]
          (if-let [status (:status decoded)]
            {:status status :headers {"content-type" "text/plain"} :body "Invalid CBOR region stroke. Nothing saved."}
            (handler (merge request decoded)))))
      (handler request))))
