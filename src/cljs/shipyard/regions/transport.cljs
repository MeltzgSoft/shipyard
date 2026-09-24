(ns shipyard.regions.transport
  "CBOR request encoding at the viewport/HTMX boundary."
  (:require ["cbor-x" :refer [Encoder]]
            [shipyard.http.forms :as forms]))

(def metadata-fields ["part-id" "mesh-key" "revision" "layer-revision" "action" "layer" "mode" "angle"])
(def encoder (Encoder. #js {:useRecords false :tagUint8Array false}))

(defn selection
  "Prefer a mask only when smaller than a uint32 vector. Bits are LSB-first."
  [triangle-count triangles]
  (let [byte-count (js/Math.ceil (/ triangle-count 8))]
    (if (< byte-count (* 4 (count triangles)))
      (let [mask (js/Uint8Array. byte-count)]
        (doseq [index triangles]
          (let [offset (bit-shift-right index 3)]
            (aset mask offset (bit-or (aget mask offset) (bit-shift-left 1 (bit-and index 7))))))
        #js ["bitset" mask])
      #js ["indices" (js/Uint32Array. (to-array triangles))])))

(defn encode [metadata triangle-count triangles]
  (let [selected (selection triangle-count triangles)]
    (.encode encoder #js [1 metadata triangle-count (aget selected 0) (aget selected 1)])))

(defn install! []
  (.defineExtension (.-htmx js/window) "region-cbor"
                    #js {:onEvent (fn [name event]
                                    (when (= name "htmx:configRequest")
                                      (aset (.. event -detail -headers) "Content-Type" "application/cbor"))
                                    true)
                         :encodeParameters (fn [_xhr _parameters ^js form]
                                             (or (.-shipyardRegionBody form)
                                                 (throw (js/Error. "No region stroke to save."))))}))

(defn post! [^js form triangle-count triangles]
  (let [metadata (js-obj)]
    (doseq [key metadata-fields]
      (aset metadata key (.-value (.namedItem (.-elements form) key))))
    (set! (.-shipyardRegionBody form) (encode metadata triangle-count triangles))
    (forms/post! form)))
