(ns shipyard.paint.delta
  "Sparse authoritative map changes with explicit removals.")

(defn between [before after]
  (let [before (or before {}) after (or after {})
        changed (into {} (filter (fn [[k v]] (not= v (get before k)))) after)
        removed (vec (remove #(contains? after %) (keys before)))]
    (if (<= (count after) (+ (count changed) (count removed)))
      {:replace after}
      {:set changed :remove removed})))

(defn apply-patch [before patch]
  (if (contains? patch :replace) (:replace patch)
      (merge (apply dissoc (or before {}) (:remove patch)) (:set patch))))
