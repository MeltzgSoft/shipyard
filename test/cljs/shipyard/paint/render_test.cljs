(ns shipyard.paint.render-test
  (:require ["three" :as three]
            [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [shipyard.paint.render :as render]
            [shipyard.paint.faces :as faces]
            [shipyard.paint.shader :as shader]))

(deftest repeated-source-shares-topology-not-paint
  (let [topology #js {} calls (atom 0) original faces/face-key
        objects (mapv (fn [_]
                        (let [geometry (three/BufferGeometry.)]
                          (.setAttribute geometry "position" (three/BufferAttribute. (js/Float32Array. #js [0 0 0 1 0 0 0 1 0]) 3))
                          (set! (.. geometry -userData -paintTopology) topology)
                          (let [object (three/Mesh. geometry (three/MeshStandardMaterial.))]
                            (set! (.. object -userData -partId) "part")
                            (set! (.. object -userData -meshKey) "hash") object))) (range 3))
        key (faces/face-key [[0 0 0] [1 0 0] [0 1 0]])
        primary {:base [0 0 1] :metalness 0 :roughness 1}]
    (with-redefs [faces/face-key (fn [vertices] (swap! calls inc) (original vertices))]
      (doseq [[i ^js object] (map-indexed vector objects)]
        (render/set-details! object {:part-id "part" :mesh-key "hash" :faces {key (assoc primary :base [(if (zero? i) 1 0) 1 0])}})
        (render/apply-details! object primary false)))
    (is (= 1 @calls) "Repeated instances build source triangle identities only once")
    (let [^js first-object (objects 0) ^js second-object (objects 1)]
      (is (= 1 (.getX (.getAttribute (.-geometry first-object) "color") 0)))
      (is (= 0 (.getX (.getAttribute (.-geometry second-object) "color") 0)) "Paint buffers stay instance-owned"))
    (doseq [^js object objects] (.dispose (.-geometry object)) (.dispose (.-material object)))))

(defn near? [a b] (< (js/Math.abs (- a b)) 0.00001))

(deftest with-finish-test
  (testing "the pinned Three.js standard shader exposes the expected PBR insertion points"
    (let [source (.-standard three/ShaderLib)
          {:keys [vertex fragment]} (shader/with-finish (.-vertexShader source) (.-fragmentShader source))]
      (is (str/includes? vertex "vShipyardFinish = shipyardFinish;"))
      (is (str/includes? fragment "roughnessFactor = vShipyardFinish.y;"))
      (is (str/includes? fragment "metalnessFactor = vShipyardFinish.x;"))
      (is (str/includes? fragment "totalEmissiveRadiance = diffuseColor.rgb * vShipyardFinish.z;"))
      (is (< (.indexOf fragment "metalnessFactor = vShipyardFinish.x;") (.indexOf fragment "#include <lights_physical_fragment>"))))))

(deftest face-finish-buffers-test
  (let [geometry (three/BufferGeometry.) surface (three/MeshStandardMaterial.)
        object (three/Mesh. geometry surface)
        inherited {:base [0 0 1] :metalness 0.2 :roughness 0.7 :glow 0.6}]
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
        (is (= 0 (.getZ finish 0)) "Old full detail materials remain unlit")
        (is (near? 0.6 (.getZ finish 3)) "RGB details inherit glow")
        (render/apply-details! object (assoc inherited :metalness 0.4) true)
        (is (false? (.-vertexColors surface)))
        (is (true? (.. surface -userData -finishEnabled -value)))
        (is (near? 0.4 (.getX finish 3)))
        (is (= 1 (.getX finish 0)))
        (render/set-details! object (update layer :faces dissoc a))
        (render/apply-details! object inherited false)
        (is (near? 0.2 (.getX finish 0)))
        (is (near? 0.7 (.getY finish 0)))
        (is (near? 0.6 (.getZ finish 0)) "Erasing restores inherited glow")
        (render/set-details! object nil)
        (render/apply-details! object inherited false)
        (is (false? (.. surface -userData -finishEnabled -value)))
        (is (empty? (.-groups geometry)))
        (.dispose geometry) (.dispose surface)))))

(deftest region-palette-under-detail-overrides
  (let [geometry (three/BufferGeometry.) surface (three/MeshStandardMaterial.) object (three/Mesh. geometry surface)
        primary {:base [0 0 1] :metalness 0 :roughness 0.9}
        trim {:base [1 0.7 0.1] :metalness 1 :roughness 0.15 :glow 0.8}]
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
        (is (near? 0.8 (.getZ finish 0)))
        (is (= 0 (.getZ finish 3)) "Glow is confined to the assigned region")
        (render/set-details! object {:part-id "part" :mesh-key "hash" :faces {key [1 0 0]}})
        (render/apply-details! object primary false)
        (is (= 1 (.getX finish 0)) "Legacy RGB detail inherits the region finish")
        (render/set-details! object {:part-id "part" :mesh-key "hash" :faces {key primary}})
        (render/apply-details! object primary false)
        (is (= 0 (.getX finish 0)))
        (is (= 0 (.getZ finish 0)) "Explicit detail suppresses the region's glow")
        (render/set-details! object nil)
        (render/apply-details! object primary false)
        (is (= 1 (.getX finish 0)) "Erasing detail reveals its region")
        (is (near? 0.8 (.getZ finish 0)))
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

(deftest numeric-region-and-detail-projection
  (let [buffer (js/ArrayBuffer. 232) header (js/Uint32Array. buffer 0 2)
        positions (js/Float32Array. buffer 8 18)
        primary {:base [0 0 1] :metalness 0 :roughness 0.9 :glow 0}
        trim {:base [1 1 0] :metalness 1 :roughness 0.2 :glow 0.8}
        detail {:base [1 0 0] :metalness 0.4 :roughness 0.6 :glow 0.3}]
    (aset header 0 1) (aset header 1 2)
    (.set positions #js [0 0 0 1 0 0 0 1 0 0 0 1 1 0 1 0 1 1])
    (let [geometry (three/BufferGeometry.) object (three/Mesh. geometry (three/MeshStandardMaterial.))]
      (.setAttribute geometry "position" (three/BufferAttribute. positions 3))
      (set! (.. geometry -userData -preparedTopology) #js {:count 2})
      (set! (.. object -userData -partId) "part") (set! (.. object -userData -meshKey) "hash")
      (render/set-regions! object {:mesh-key "hash" :triangle-layers (js/Uint32Array. #js [1 0]) :layer-table ["Primary" "Trim"]} {"Primary" primary "Trim" trim})
      (render/set-details! object {:part-id "part" :mesh-key "hash" :triangle-details (js/Uint32Array. #js [0 1]) :detail-table [nil detail]})
      (render/apply-details! object primary false)
      (let [finish (.getAttribute geometry "shipyardFinish")]
        (is (= 1 (.getX finish 0)))
        (is (near? 0.8 (.getZ finish 0)))
        (is (near? 0.4 (.getX finish 3)))
        (is (near? 0.3 (.getZ finish 3)))
        (is (false? (.. object -userData -paintPreparing)))
        (render/set-regions! object (.. object -userData -paintRegions) {"Primary" primary "Trim" (assoc trim :metalness 0.65 :base [0 1 0])})
        (render/apply-details! object primary false)
        (is (near? 0.65 (.getX finish 0)) "Changing a numeric region palette refreshes finish")
        (is (= 0 (.getX (.getAttribute geometry "color") 0)))
        (is (= 1 (.getY (.getAttribute geometry "color") 0)))
        (render/apply-details! object primary true)
        (is (false? (.. object -material -vertexColors)))
        (is (true? (.. object -material -userData -finishEnabled -value)))
        (render/set-regions! object {:mesh-key "hash" :projection-reset? true :triangle-layers (js/Uint32Array. #js [1 0]) :layer-table ["Primary" "Trim"]} {"Primary" primary "Trim" trim})
        (render/apply-details! object primary false)
        (is (= 0 (.getX (.getAttribute geometry "shipyardFinish") 0)) "Authoritative region replacement discards the old ordinal baseline")))))
