(ns shipyard.part-browser.thumbnail
  "Small shaded previews with source-bound region colors and saved orientation."
  (:require [shipyard.math :as math]
            [shipyard.paint.faces :as faces]
            [shipyard.regions.model :as regions]
            [shipyard.part.orientation :as orientation])
  (:import [java.awt Color RenderingHints]
           [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream]
           [javax.imageio ImageIO]))

(defn source-regions [saved mesh-key]
  (when (= mesh-key (:mesh-key saved)) saved))

(defn region-style [saved]
  (let [palette (regions/preview-materials (or saved {:layers regions/builtins}))]
    {:primary (get-in palette ["Primary" :base])
     :faces (or (:faces saved) {})
     :colors (into {} (for [layer (distinct (vals (:faces saved)))]
                        [layer (get-in palette [layer :base])]))}))

(defn region-mesh
  "Color source triangles before orientation, using the same palette as the Regions tab."
  [mesh saved]
  (let [{:keys [primary colors] assignments :faces} (region-style saved)
        vertices (when (seq assignments) (mapv vec (partition 3 (:positions mesh))))]
    (assoc mesh :colors
           (mapv (fn [ids]
                   (let [layer (when vertices (get assignments (faces/face-key (mapv vertices ids))))]
                     (get colors layer primary)))
                 (partition 3 (:indices mesh))))))

(defn triangles [{:keys [positions indices colors]} pose]
  (let [forward (math/normalize [3.0 2.6 4.0])
        right (math/normalize (math/cross [0.0 1.0 0.0] forward))
        up (math/cross forward right)
        points (mapv (fn [p]
                       (let [p (orientation/rotate-vector pose p)]
                         [(math/dot p right) (- (math/dot p up)) (math/dot p forward)]))
                     (partition 3 positions))
        xs (map first points) ys (map second points)
        xmin (reduce min xs) xmax (reduce max xs)
        ymin (reduce min ys) ymax (reduce max ys)
        scale (min (/ 112.0 (max 0.001 (- xmax xmin))) (/ 72.0 (max 0.001 (- ymax ymin))))
        cx (/ (+ xmin xmax) 2.0) cy (/ (+ ymin ymax) 2.0)]
    (->> (partition 3 indices)
         (map-indexed (fn [i ids]
                        (let [[a b c :as vertices] (mapv points ids)
                              normal (math/normalize (math/cross (mapv - b a) (mapv - c a)))
                              shade (+ 85 (int (* 125 (Math/abs (double (math/dot (or normal [0 0 1]) [0.3 -0.6 0.74]))))))]
                          {:depth (reduce + (map #(nth % 2) vertices)) :shade (min 230 shade)
                           :color (get colors i)
                           :points (mapv (fn [[x y]] [(+ 64 (* scale (- x cx))) (+ 44 (* scale (- y cy)))]) vertices)})))
         (sort-by :depth))))

(defn png!
  ([mesh pose] (png! mesh pose 1))
  ([mesh pose scale]
   (let [image (BufferedImage. (* 128 scale) (* 88 scale) BufferedImage/TYPE_INT_RGB)
         graphics (.createGraphics image)
         output (ByteArrayOutputStream.)]
     (try
       (.scale graphics (double scale) (double scale))
       (.setColor graphics (Color. 20 23 28))
       (.fillRect graphics 0 0 128 88)
       (.setRenderingHint graphics RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
       (doseq [{:keys [points shade color]} (triangles mesh pose)]
         (.setColor graphics (if color
                               (let [[r g b] (map #(* shade %) color)] (Color. (int r) (int g) (int b)))
                               (Color. (int (* shade 0.85)) (int (* shade 0.93)) (int shade))))
         (.fillPolygon graphics (int-array (map first points)) (int-array (map second points)) 3))
       (ImageIO/write image "png" output)
       (.toByteArray output)
       (finally (.dispose graphics))))))
