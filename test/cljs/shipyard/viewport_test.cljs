(ns shipyard.viewport-test
  (:require ["three" :as three]
            [cljs.test :refer-macros [deftest is testing]]
            [shipyard.viewport :as viewport]))

(defn- ensure-document! []
  (when-not (exists? js/document)
    (set! (.-document js/globalThis)
          #js {:getElementById (fn [_] nil)})))

(defn- system []
  (ensure-document!)
  {:scene (three/Scene.)
   :orientation-scene (three/Scene.)
   :canvas #js {:classList #js {:remove (fn [_] nil)}}
   :parts (atom {})
   :preview (atom nil)
   :interfaces (atom nil)
   :mount-markers (atom {})
   :mount-colors-enabled (atom true)
   :orientation-guide (atom nil)
   :authoring (atom {:state :enter})
   :current (atom {:part-id "hull"})
   :repeat (atom {:mount-id "mount-2"})})

(defn- disposed-counts []
  (atom {:geometry 0 :material 0}))

(defn- part [name]
  (let [geometry (three/BufferGeometry.)
        material (three/MeshStandardMaterial.)
        obj (three/Mesh. geometry material)
        disposed (disposed-counts)]
    (set! (.-name obj) name)
    (.addEventListener geometry "dispose" #(swap! disposed update :geometry inc))
    (.addEventListener material "dispose" #(swap! disposed update :material inc))
    {:obj obj :disposed disposed}))

(defn- child-names [{:keys [^js scene]}]
  (mapv #(.-name ^js %) (array-seq (.-children scene))))

(defn- part-ids [{:keys [parts]}]
  (vec (keys @parts)))

(deftest put-part!-test
  (testing "adds a part without needing a renderer"
    (let [sys (system)
          a (part "hull")]
      (viewport/put-part! sys "hull" (:obj a))
      (is (= ["hull"] (child-names sys)))
      (is (= ["hull"] (part-ids sys)))
      (is (= {:geometry 0 :material 0} @(:disposed a)))))

  (testing "reselecting the same part replaces and disposes the old object"
    (let [sys (system)
          old (part "old hull")
          new (part "new hull")]
      (viewport/put-part! sys "hull" (:obj old))
      (viewport/put-part! sys "hull" (:obj new))
      (is (= ["new hull"] (child-names sys)))
      (is (= ["hull"] (part-ids sys)))
      (is (= {:geometry 1 :material 1} @(:disposed old)))
      (is (= {:geometry 0 :material 0} @(:disposed new))))))

(deftest show-only!-test
  (testing "selecting another part replaces rather than accumulates"
    (let [sys (system)
          hull (part "hull")
          prow (part "prow")]
      (viewport/put-part! sys "hull" (:obj hull))
      (viewport/show-only! sys "prow" (:obj prow))
      (is (= ["prow"] (child-names sys)))
      (is (= ["prow"] (part-ids sys)))
      (is (= {:geometry 1 :material 1} @(:disposed hull)))
      (is (= {:geometry 0 :material 0} @(:disposed prow)))))

  (testing "selecting the same part still replaces the old object"
    (let [sys (system)
          old (part "old prow")
          new (part "new prow")]
      (viewport/show-only! sys "prow" (:obj old))
      (viewport/show-only! sys "prow" (:obj new))
      (is (= ["new prow"] (child-names sys)))
      (is (= ["prow"] (part-ids sys)))
      (is (= {:geometry 1 :material 1} @(:disposed old))))))

(deftest clear!-test
  (testing "clear empties bookkeeping and disposes everything in the scene"
    (let [sys (system)
          hull (part "hull")
          prow (part "prow")]
      (viewport/put-part! sys "hull" (:obj hull))
      (viewport/put-part! sys "prow" (:obj prow))
      (viewport/clear! sys)
      (is (empty? (child-names sys)))
      (is (empty? (part-ids sys)))
      (is (nil? @(:authoring sys)))
      (is (nil? @(:current sys)))
      (is (nil? @(:repeat sys)))
      (is (= {:geometry 1 :material 1} @(:disposed hull)))
      (is (= {:geometry 1 :material 1} @(:disposed prow)))))

  (testing "clearing an already empty scene is harmless"
    (let [sys (system)]
      (viewport/clear! sys)
      (is (empty? (child-names sys)))
      (is (empty? (part-ids sys))))))
