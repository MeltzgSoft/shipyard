(ns shipyard.emission-benchmark
  "Synthetic, machine-specific emission preparation allocation/timing probe."
  (:require [shipyard.paint.emission :as emission])
  (:import [java.lang.management ManagementFactory]
           [com.sun.management ThreadMXBean]))

(defn- measure! [run!]
  (let [^ThreadMXBean bean (ManagementFactory/getThreadMXBean)
        id (.threadId (Thread/currentThread))
        before (.getThreadAllocatedBytes bean id) start (System/nanoTime)
        value (run!)]
    {:value value :milliseconds (/ (- (System/nanoTime) start) 1e6)
     :allocated-bytes (- (.getThreadAllocatedBytes bean id) before)}))

(defn -main [& [count-string]]
  (let [triangles (or (some-> count-string (parse-long)) 100000)
        mesh {:positions (float-array (mapcat (fn [i] [i 0 0 (inc i) 0 0 i 1 0]) (range triangles)))
              :indices (int-array (range (* 3 triangles)))}
        source (measure! #(emission/source-moments mesh))
        keys (vec (keys (:value source)))
        regions {:mesh-key "synthetic" :faces (zipmap (take-nth 2 keys) (repeat "Secondary"))}
        initial (measure! #(emission/group-moments (:value source) "synthetic" regions nil))
        repeated (measure! #(dotimes [_ 100] (emission/group-moments (:value source) "synthetic" regions nil)))]
    (prn {:triangles triangles :java (System/getProperty "java.version")
          :source (dissoc source :value) :first-mask (dissoc initial :value)
          :repeated-100-masks (dissoc repeated :value)
          :palette-reaggregation-triangles 0 :summary-groups (count (:value initial))})))
