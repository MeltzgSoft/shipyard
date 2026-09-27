(ns shipyard.paint.glow-test
  (:require ["three" :as three]
            [cljs.test :refer-macros [deftest is]]
            [shipyard.paint.glow :as glow]
            [shipyard.paint.render :as render]))

(deftest sources-follow-visible-face-materials
  (let [geometry (three/BufferGeometry.) surface (three/MeshStandardMaterial.) object (three/Mesh. geometry surface)
        primary {:base [1 0 0] :metalness 0 :roughness 1 :glow 0}]
    (.setAttribute geometry "position" (three/BufferAttribute. (js/Float32Array. #js [0 0 0 1 0 0 0 1 0]) 3))
    (set! (.. object -userData -partId) "part")
    (set! (.. object -userData -meshKey) "hash")
    (set! (.-emissiveIntensity surface) 0)
    (is (empty? (glow/sources [object])))
    (render/set-details! object {:part-id "part" :mesh-key "hash"
                                 :faces {(render/face-key geometry 0) (assoc primary :glow 1)}})
    (render/apply-details! object primary false)
    (let [sources (glow/sources [object]) {:keys [^js position ^js color power]} (first sources)]
      (is (= 1 (count sources)))
      (is (= 0.5 power))
      (is (pos? (.-z position)) "Light sits outside the emitting face")
      (is (= "ff0000" (.getHexString color)))
      (is (= glow/light-limit (count (glow/sources (repeat 20 object)))))
      (.makeScale (.-matrixWorld object) 2 2 2)
      (is (= 2 (:power (first (glow/sources [object])))) "Lighting scales with model units"))
    (render/set-details! object nil)
    (render/apply-details! object primary false)
    (is (empty? (glow/sources [object])) "Erasing the glowing face removes its light")
    (.dispose geometry) (.dispose surface)))
