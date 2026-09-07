(ns shipyard.mount.split
  "Equal-width/height socket sections in the authored face frame."
  (:require [shipyard.math :as math]))

(defn face-bounds
  "Project picked source points onto mount X/Y, relative to its origin."
  [{:mount/keys [pos axis roll]} points]
  (when (seq points)
    (let [up (math/cross axis roll)
          projected (map (fn [p]
                           (let [v (math/subtract p pos)]
                             [(math/dot v roll) (math/dot v up)])) points)]
      [(apply mapv min projected) (apply mapv max projected)])))

(defn valid-bounds? [bounds]
  (boolean
   (and (vector? bounds) (= 2 (count bounds))
        (every? #(and (vector? %) (= 2 (count %)) (every? math/finite-number? %)) bounds)
        (every? true? (map < (first bounds) (second bounds))))))

(defn sections
  "Return ordered frames and boundary segments, or actionable incomplete-authoring data.
  Vertical divides +X width; horizontal divides +Y height. Ordinals increase along that axis."
  [{:mount/keys [capacity split pos axis roll] :as mount}]
  (let [capacity (or capacity 1)
        {:keys [direction bounds]} split]
    (cond
      (not (and (integer? capacity) (<= 1 capacity 256)))
      {:error :invalid-capacity :message "Capacity must be between 1 and 256."}

      (= 1 capacity) {:frames [mount] :lines []}

      (not (and (#{:vertical :horizontal} direction) (valid-bounds? bounds)))
      {:error :incomplete-split
       :message "Pick the socket face again and choose a vertical or horizontal split."}

      :else
      (let [[[xmin ymin] [xmax ymax]] bounds
            vertical? (= :vertical direction)
            lower (if vertical? xmin ymin)
            upper (if vertical? xmax ymax)
            step (/ (- upper lower) capacity)
            up (math/cross axis roll)
            point (fn [x y] (math/add pos (math/add (math/scale (double x) roll)
                                                    (math/scale (double y) up))))
            centers (mapv #(+ lower (* (+ % 0.5) step)) (range capacity))
            cuts (mapv #(+ lower (* % step)) (range 1 capacity))]
        {:frames (mapv (fn [center]
                         (assoc mount :mount/pos
                                (if vertical?
                                  (point center (/ (+ ymin ymax) 2.0))
                                  (point (/ (+ xmin xmax) 2.0) center)))) centers)
         :lines (mapv (fn [cut]
                        (if vertical?
                          [(point cut ymin) (point cut ymax)]
                          [(point xmin cut) (point xmax cut)])) cuts)}))))
