(ns shipyard.jobs.transforms
  "Explicit, memory-conscious defaults for application background work.")

(defn limits [{:keys [threads queue-size]}]
  (let [limits {:threads (if (nil? threads) 2 threads)
                :queue-size (if (nil? queue-size) 32 queue-size)}]
    (when-not (every? #(and (int? %) (pos? %) (<= % Integer/MAX_VALUE)) (vals limits))
      (throw (ex-info "Job threads and queue-size must be positive integers" limits)))
    limits))
