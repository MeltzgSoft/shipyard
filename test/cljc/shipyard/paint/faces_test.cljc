(ns shipyard.paint.faces-test
  (:require [shipyard.paint.faces :as faces]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def metal {:base [1 0.7 0.1] :metalness 1 :roughness 0.15})
(def inherited {:base [0 0 1] :metalness 0 :roughness 0.9})

(deftest resolve-material-test
  (testing "details override all material channels"
    (is (= inherited (faces/resolve-material inherited nil)))
    (is (= inherited (faces/resolve-material inherited [1 0 0])))
    (is (= metal (faces/resolve-material inherited metal)))
    (is (= metal (faces/resolve-material (assoc inherited :glow 1) metal)) "Absent glow defaults to zero")
    (is (= (assoc inherited :glow 1) (faces/resolve-material (assoc inherited :glow 1) nil)))
    (is (= metal (faces/resolve-material (assoc inherited :roughness 0.3) metal)))))

(deftest mixed-layer-stroke-test
  (let [a (faces/face-key [[0 0 0] [1 0 0] [0 1 0]])
        b (faces/face-key [[0 0 1] [1 0 1] [0 1 1]])
        hash (apply str (repeat 64 "a"))
        old (:layer (faces/stroke nil "part" hash [a] inherited false))
        updated (:layer (faces/stroke old "part" hash [b] metal false))]
    (is (= {a inherited b metal} (:faces updated)))
    (is (= old (:layer (faces/stroke updated "part" hash [b] metal true))))
    (is (= :invalid-material (:error (faces/stroke old "part" hash [b] (assoc metal :roughness ##NaN) false))))))
