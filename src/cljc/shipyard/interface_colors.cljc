(ns shipyard.interface-colors
  "Shared color classification for configured mount interfaces.")

(def colors
  {:plug    "#69d2c0"
   :socket  "#b8c1cc"
   :multi   "#d8a25f"
   :hull    "#8fd8ff"
   :prow    "#f0c65a"
   :bridge  "#c792ea"
   :antenna "#a3e635"
   :engine  "#f97316"
   :weapon  "#ff7a90"
   :turret  "#79a9ff"})

(defn type-of
  [{:mount/keys [kind accepts]}]
  (if (= :plug kind)
    :plug
    (let [roles (sort-by name accepts)]
      (case (count roles)
        0 :socket
        1 (first roles)
        :multi))))

(defn label
  [interface-type]
  (case interface-type
    :plug "plug"
    :socket "socket"
    :multi "multi-role socket"
    (str (name interface-type) " socket")))

(defn color
  [interface-type]
  (get colors interface-type (:socket colors)))

(defn legend-items
  [mounts]
  (->> mounts
       (map type-of)
       (distinct)
       (sort-by (comp label))
       (mapv (fn [interface-type]
               {:type interface-type
                :label (label interface-type)
                :color (color interface-type)}))))
