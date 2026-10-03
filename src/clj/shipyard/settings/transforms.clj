(ns shipyard.settings.transforms
  "Pure mount-cut defaults and form validation.")

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
