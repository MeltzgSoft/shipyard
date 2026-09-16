(ns shipyard.bulk-orientation.save-state
  "Pure correlation of an Orient submission with its durable acknowledgement."
  (:require [shipyard.part.orientation :as orientation]))

(defn submission [request activation entries]
  {:request request :activation activation
   :poses (into {} (keep (fn [[id {:keys [orientation dirty]}]]
                           (when dirty [id orientation]))) entries)})

(defn same-pose? [a b]
  (let [a (orientation/normalize-quaternion a)
        b (orientation/normalize-quaternion b)]
    (boolean (and a b (< (- 1.0 (abs (reduce + (map * a b)))) 1.0e-10)))))

(defn acknowledge
  "Only submitted, successful parts advance. Newer edits and acknowledgements survive."
  [entries submitted response activation]
  (if (and submitted (= activation (:activation submitted) (:activation response))
           (= (:request submitted) (:request response)))
    (reduce (fn [result id]
              (let [entry (get result id)
                    saved (get-in submitted [:poses id])
                    request (:request submitted)]
                (if (and entry saved (> request (get entry :saved-request -1)))
                  (assoc result id (assoc entry :saved saved :saved-request request
                                          :dirty (not (same-pose? saved (:orientation entry)))))
                  result)))
            entries (:saved response))
    entries))

(defn newer-request? [previous order]
  (or (nil? order) (nil? previous) (pos? (compare order previous))))

(defn restore-baseline
  "A restored grid uses current durable metadata while retaining dirty preview edits."
  [entry durable]
  (let [saved (orientation/orientation-of durable)]
    (assoc entry :saved saved :orientation (if (:dirty entry) (:orientation entry) saved))))
