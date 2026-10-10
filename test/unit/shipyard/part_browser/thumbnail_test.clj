(ns shipyard.part-browser.thumbnail-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.paint.faces :as faces]
            [shipyard.part-browser.thumbnail :as thumbnail]
            [shipyard.regions.model :as regions])
  (:import [java.io ByteArrayInputStream]
           [javax.imageio ImageIO]))

(deftest thumbnail-background-is-transparent-with-opaque-geometry
  (doseq [scale [1 2]]
    (let [mesh {:positions [0 0 0 1 0 0 0 1 0] :indices [0 1 2]}
          image (ImageIO/read (ByteArrayInputStream. (thumbnail/png! mesh nil scale)))
          alpha #(bit-and 255 (unsigned-bit-shift-right (.getRGB image %1 %2) 24))]
      (is (zero? (alpha 0 0)) "The UI palette supplies the background in either theme")
      (is (some #(= 255 (apply alpha %))
                (for [x (range (* 128 scale)) y (range (* 88 scale))] [x y]))
          "Rendered geometry remains opaque"))))

(deftest region-style-ignores-unused-layers-and-revisions
  (is (= (thumbnail/region-style nil)
         (thumbnail/region-style {:revision 4 :layers ["Primary" "Unused"] :faces {}}))))

(deftest source-regions-use-the-editor-palette
  (let [points [[0 0 0] [1 0 0] [0 1 0] [0 0 1]]
        mesh {:positions (vec (mapcat identity points)) :indices [0 1 2 0 2 3 0 1 3]}
        saved {:mesh-key "original" :layers ["Primary" "Secondary" "layer:trim"]
               :layer-definitions {"layer:trim" {:preview-color [0.9 0.2 0.1]}}
               :faces {(faces/face-key (mapv points [0 1 2])) "Secondary"
                       (faces/face-key (mapv points [0 2 3])) "layer:trim"}}
        palette (regions/preview-materials saved)
        colored (thumbnail/region-mesh mesh (thumbnail/source-regions saved "original"))]
    (is (= [(get-in palette ["Secondary" :base]) [0.9 0.2 0.1] (get-in palette ["Primary" :base])]
           (:colors colored)))
    (is (= mesh (dissoc colored :colors)))
    (is (nil? (thumbnail/source-regions saved "replacement"))))
  (is (= [[0.6 0.65 0.7]] (:colors (thumbnail/region-mesh {:positions [0 0 0 1 0 0 0 1 0] :indices [0 1 2]} nil)))))
