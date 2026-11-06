(ns shipyard.settings.transforms
  "Pure mount-cut defaults and form validation."
  (:require [shipyard.math :as math]))

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

(def seam-default 0.005)

(defn valid-seam? [value]
  (boolean (and (math/finite-number? value) (<= 0 value 0.1))))

(defn seam-settings [params]
  (let [value (math/parse-finite-double (get params "seam-tolerance-mm"))]
    (if (valid-seam? value)
      {:value value}
      {:error "Mount face seam tolerance must be a finite number from 0 to 0.1 mm."})))
