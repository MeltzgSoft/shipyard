(ns shipyard.region-fixture
  (:require [shipyard.catalog.db :as catalog]
            [shipyard.regions.registry :as registry]))

(defn id [cat name]
  (first (keep (fn [[id entry]] (when (= name (:name entry)) id))
               (registry/definitions (catalog/region-registry (catalog/snapshot! cat))))))

(defn names [cat]
  (let [shared (catalog/region-registry (catalog/snapshot! cat))]
    (mapv #(get-in (registry/definitions shared) [% :name]) (registry/ids shared))))

(defn cbor-stroke
  "Independent JVM writer for real HTTP boundary tests."
  ([metadata triangle-count encoding payload]
   (cbor-stroke metadata triangle-count encoding payload {}))
  ([metadata triangle-count encoding payload {:keys [version tag] :or {version 1}}]
   (let [out (java.io.ByteArrayOutputStream.)]
     (with-open [writer (.createGenerator (com.fasterxml.jackson.dataformat.cbor.CBORFactory.) out)]
       (.writeStartArray writer nil 5)
       (.writeNumber writer (long version))
       (.writeStartObject writer)
       (doseq [[key value] metadata]
         (.writeStringField writer (name key) (str value)))
       (.writeEndObject writer)
       (.writeNumber writer (long triangle-count))
       (.writeString writer encoding)
       (when-let [tag (or tag (when (= encoding "indices") 70))] (.writeTag writer (int tag)))
       (.writeBinary writer ^bytes payload)
       (.writeEndArray writer))
     (.toByteArray out))))

(defn uint32-bytes [indices]
  (let [buffer (doto (java.nio.ByteBuffer/allocate (* 4 (count indices)))
                 (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
    (doseq [index indices] (.putInt buffer (unchecked-int index)))
    (.array buffer)))
