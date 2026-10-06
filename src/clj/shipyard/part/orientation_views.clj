(ns shipyard.part.orientation-views
  "Shared absolute Euler degree fields; callers retain their own save contracts.")

(defn display-angle [value]
  (/ (Math/round (* 100.0 (double value))) 100.0))

(defn angle-fields [names angles {:keys [label-class input-attrs]}]
  (for [[label field value] (map vector ["Yaw (Y) °" "Pitch (X) °" "Roll (Z) °"] names angles)]
    [:label (cond-> {} label-class (assoc :class label-class)) label
     [:input (merge {:type "number" :name field :value (display-angle value) :step "any"}
                    input-attrs)]]))
