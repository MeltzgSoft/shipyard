(ns shipyard.preparation.transforms
  "Bounded immutable preparation cache decisions.")

(defn trim
  "Evict least recently used completed entries; running jobs remain admitted by jobs."
  [entries cap-bytes max-entries]
  (loop [entries entries]
    (let [completed (filter (comp #{:ready :failed} :state val) entries)
          bytes (reduce + 0 (map (comp #(or % 0) :size val) completed))]
      (if (and (seq completed) (or (> bytes cap-bytes) (> (count completed) max-entries)))
        (recur (dissoc entries (key (apply min-key (comp :access val) completed))))
        entries))))

(defn envelope [entry]
  (select-keys entry [:state :resource :message]))
