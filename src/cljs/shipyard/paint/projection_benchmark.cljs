(ns shipyard.paint.projection-benchmark
  "On-demand numeric/text snapshot timing and live heap allocation comparison."
  (:require [shipyard.paint.projection :as projection]))

(defn- measure! [run!]
  (let [before (.-heapUsed (.memoryUsage js/process)) start (.now js/performance)
        value (run!)]
    {:value value :milliseconds (- (.now js/performance) start)
     :heap-growth-bytes (- (.-heapUsed (.memoryUsage js/process)) before)}))

(defn main! []
  (let [triangles (js/Number (or (aget (.-argv js/process) 2) "100000"))
        metadata {:mesh-key "synthetic" :region-revision "1"
                  :layer-table ["Primary" "Secondary"] :detail-table [nil]}
        buffer (projection/encode metadata (vec (repeat triangles 1)) (vec (repeat triangles 0)))
        mask (js/Object.)]
    (dotimes [index triangles] (aset mask (.padStart (.toString index 16) 72 "0") "Secondary"))
    (let [text (js/JSON.stringify mask)
          json (measure! #(js/JSON.parse text))
          persistent (measure! #(js->clj (:value json)))
          numeric (measure! #(projection/decode buffer))
          repeat-numeric (measure! #(dotimes [_ 100] (projection/decode buffer)))]
      (println (pr-str {:triangles triangles :node (.-version js/process)
                        :binary-bytes (.-byteLength buffer) :json-bytes (.-length text)
                        :json-parse (dissoc json :value) :persistent-map (dissoc persistent :value)
                        :numeric-decode (dissoc numeric :value) :repeated-100-decodes (dissoc repeat-numeric :value)
                        :typed-view-bytes (+ (.-byteLength (:triangle-layers (:value numeric)))
                                             (.-byteLength (:triangle-details (:value numeric))))})))))
