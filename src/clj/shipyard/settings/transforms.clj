(ns shipyard.settings.transforms
  "Pure settings defaults and form validation."
  (:require [shipyard.fleet.transforms :as fleet]))

(def default-variant-preference fleet/default-variant-preference)
(def variant-preference? fleet/variant-preference?)

(defn move-variant [preference variant direction]
  (let [i (.indexOf ^java.util.List preference variant)
        j (+ i (case direction :up -1 :down 1 0))]
    (if (and (<= 0 i) (< -1 j (count preference)))
      (assoc preference i (nth preference j) j variant)
      preference)))

(def cut-defaults {:pit {:depth 1.0 :diameter 2.0}
                   :recess {:depth 1.0 :border 0.5}})

(def cut-fields [["pit-depth" :pit :depth]
                 ["pit-diameter" :pit :diameter]
                 ["recess-depth" :recess :depth]
                 ["recess-border" :recess :border]])

(defn cut-settings [params]
  (reduce (fn [result [field kind dimension]]
            (let [n (try (Double/parseDouble (get params field ""))
                         (catch NumberFormatException _ nil))]
              (if (and n (Double/isFinite n)
                       (if (= dimension :border) (<= 0 n) (< 0 n)))
                (assoc-in result [:values kind dimension] n)
                (assoc result :error "Depth and diameter must be positive; border must be zero or greater. All values must be finite millimeters."))))
          {} cut-fields))
