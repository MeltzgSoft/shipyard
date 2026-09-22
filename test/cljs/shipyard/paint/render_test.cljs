(ns shipyard.paint.render-test
  (:require ["three" :as three]
            [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [shipyard.paint.render :as render]
            [shipyard.paint.shader :as shader]))

(defn near? [a b] (< (js/Math.abs (- a b)) 0.00001))

(deftest with-finish-test
  (testing "the pinned Three.js standard shader exposes the expected PBR insertion points"
    (let [source (.-standard three/ShaderLib)
          {:keys [vertex fragment]} (shader/with-finish (.-vertexShader source) (.-fragmentShader source))]
      (is (str/includes? vertex "vShipyardFinish = shipyardFinish;"))
      (is (str/includes? fragment "roughnessFactor = vShipyardFinish.y;"))
      (is (str/includes? fragment "metalnessFactor = vShipyardFinish.x;"))
      (is (< (.indexOf fragment "metalnessFactor = vShipyardFinish.x;") (.indexOf fragment "#include <lights_physical_fragment>"))))))

(deftest face-finish-buffers-test
  (let [geometry (three/BufferGeometry.) surface (three/MeshStandardMaterial.)
        object (three/Mesh. geometry surface)
        inherited {:base [0 0 1] :metalness 0.2 :roughness 0.7}]
    (.setAttribute geometry "position" (three/BufferAttribute. (js/Float32Array. #js [0 0 0 1 0 0 0 1 0 0 0 1 1 0 1 0 1 1]) 3))
    (set! (.. object -userData -partId) "part")
    (set! (.. object -userData -meshKey) "hash")
    (let [a (render/face-key geometry 0) b (render/face-key geometry 1)
          layer {:part-id "part" :mesh-key "hash" :faces {a {:base [1 0.7 0.1] :metalness 1 :roughness 0.15} b [1 0 0]}}]
      (render/set-details! object layer)
      (render/apply-details! object inherited false)
      (let [finish (.getAttribute geometry "shipyardFinish")]
        (is (= 1 (.getX finish 0)))
        (is (near? 0.15 (.getY finish 0)))
        (is (near? 0.2 (.getX finish 3)))
        (render/apply-details! object (assoc inherited :metalness 0.4) true)
        (is (false? (.-vertexColors surface)))
        (is (true? (.. surface -userData -finishEnabled -value)))
        (is (near? 0.4 (.getX finish 3)))
        (is (= 1 (.getX finish 0)))
        (render/set-details! object (update layer :faces dissoc a))
        (render/apply-details! object inherited false)
        (is (near? 0.2 (.getX finish 0)))
        (is (near? 0.7 (.getY finish 0)))
        (render/set-details! object nil)
        (render/apply-details! object inherited false)
        (is (false? (.. surface -userData -finishEnabled -value)))
        (is (empty? (.-groups geometry)))
        (.dispose geometry) (.dispose surface)))))

(deftest region-palette-under-detail-overrides
  (let [geometry (three/BufferGeometry.) surface (three/MeshStandardMaterial.) object (three/Mesh. geometry surface)
        primary {:base [0 0 1] :metalness 0 :roughness 0.9}
        trim {:base [1 0.7 0.1] :metalness 1 :roughness 0.15}]
    (.setAttribute geometry "position" (three/BufferAttribute. (js/Float32Array. #js [0 0 0 1 0 0 0 1 0 0 0 1 1 0 1 0 1 1]) 3))
    (set! (.. object -userData -partId) "part")
    (set! (.. object -userData -meshKey) "hash")
    (let [key (render/face-key geometry 0)
          regions {:mesh-key "hash" :faces {key "Trim"}}
          layers {"Primary" primary "Trim" trim}]
      (render/set-regions! object regions layers)
      (render/apply-details! object primary false)
      (let [finish (.getAttribute geometry "shipyardFinish")]
        (is (= 1 (.getX finish 0)))
        (is (= 0 (.getX finish 3)))
        (render/set-details! object {:part-id "part" :mesh-key "hash" :faces {key [1 0 0]}})
        (render/apply-details! object primary false)
        (is (= 1 (.getX finish 0)) "Legacy RGB detail inherits the region finish")
        (render/set-details! object {:part-id "part" :mesh-key "hash" :faces {key primary}})
        (render/apply-details! object primary false)
        (is (= 0 (.getX finish 0)))
        (render/set-details! object nil)
        (render/apply-details! object primary false)
        (is (= 1 (.getX finish 0)) "Erasing detail reveals its region")
        (render/set-regions! object regions (assoc layers "Trim" (assoc trim :metalness 0.3)))
        (render/apply-details! object primary true)
        (is (near? 0.3 (.getX finish 0)))
        (is (false? (.-vertexColors surface)))
        (render/set-regions! object regions nil)
        (render/apply-details! object primary false)
        (is (false? (.-vertexColors surface)) "Instance or group override hides region colors")
        (render/set-regions! object (assoc regions :mesh-key "changed") layers)
        (render/apply-details! object primary false)
        (is (false? (.-vertexColors surface)) "Changed sources do not apply old region masks")
        (.dispose geometry) (.dispose surface)))))
