(ns shipyard.loadout.identity
  "Shared saved-model identity validation, independent of paint or persistence."
  (:require [clojure.string :as str]))

(defn name? [value]
  (and (string? value) (<= 1 (count value) 200) (not (str/blank? value))))

(defn part-id? [value]
  (and (string? value) (<= 1 (count value) 2048)))

(defn slot-path? [path]
  (and (vector? path) (<= 1 (count path) 16)
       (every? #(and (vector? %) (= 2 (count %))
                     (keyword? (first %)) (integer? (second %))
                     (<= 0 (second %) 255)) path)))
