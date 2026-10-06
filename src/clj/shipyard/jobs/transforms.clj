(ns shipyard.jobs.transforms
  "Pure execution limits, bounded admission and fair dispatch decisions.")

(defn limits [{:keys [threads queue-size interactive-reserve]}]
  (let [threads (if (nil? threads) 2 threads)
        queue-size (if (nil? queue-size) 4096 queue-size)]
    (when-not (every? #(and (int? %) (pos? %) (<= % Integer/MAX_VALUE)) [threads queue-size])
      (throw (ex-info "Job threads and queue-size must be positive integers" {:threads threads :queue-size queue-size})))
    (let [reserve (if (nil? interactive-reserve) (min 32 (quot queue-size 4)) interactive-reserve)]
      (when-not (and (int? reserve) (<= 0 reserve) (< reserve queue-size))
        (throw (ex-info "Interactive reserve must be nonnegative and smaller than queue-size" {:interactive-reserve reserve :queue-size queue-size})))
      {:threads threads :queue-size queue-size :interactive-reserve reserve})))

(defn admits? [{:keys [queue-size interactive-reserve]} priority pending bulk added]
  (and (<= (+ pending added) queue-size)
       (or (= priority :interactive) (<= (+ bulk added) (- queue-size interactive-reserve)))))

(defn next-priority [interactive? bulk? streak]
  (cond
    (and bulk? (or (not interactive?) (>= streak 3))) :bulk
    interactive? :interactive
    bulk? :bulk))

(defn unique-descriptors [descriptors]
  (:items (reduce (fn [{:keys [seen] :as result} descriptor]
                    (if (contains? seen (:key descriptor)) result
                        (-> result (update :seen conj (:key descriptor)) (update :items conj descriptor))))
                  {:seen #{} :items []} descriptors)))
