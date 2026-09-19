(ns shipyard.paint.faces-test
  (:require [shipyard.paint.faces :as faces]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def metal {:base [1 0.7 0.1] :metalness 1 :roughness 0.15})
(def inherited {:base [0 0 1] :metalness 0 :roughness 0.9})

(deftest paint?-test
  (testing "legacy colours and complete finite materials are supported"
    (is (faces/paint? [1 0 0]))
    (is (faces/paint? metal))
    (doseq [value [nil {} [] (dissoc metal :roughness) (assoc metal :extra 1)
                   (assoc metal :base [1 2 3]) (assoc metal :metalness ##NaN)
                   (assoc metal :roughness ##Inf) (assoc metal :roughness -0.1)
                   (assoc metal :metalness 1.01) (assoc metal :metalness "1")]]
      (is (not (faces/paint? value))))))

(deftest resolve-material-test
  (testing "legacy entries track inherited finish; new entries freeze all channels"
    (is (= inherited (faces/resolve-material inherited nil)))
    (is (= (assoc inherited :base [1 0 0]) (faces/resolve-material inherited [1 0 0])))
    (is (= metal (faces/resolve-material inherited metal)))
    (is (= metal (faces/resolve-material (assoc inherited :roughness 0.3) metal)))))

(deftest mixed-layer-stroke-test
  (let [a (faces/face-key [[0 0 0] [1 0 0] [0 1 0]])
        b (faces/face-key [[0 0 1] [1 0 1] [0 1 1]])
        hash (apply str (repeat 64 "a"))
        old (:layer (faces/stroke nil "part" hash [a] [1 0 0] false))
        updated (:layer (faces/stroke old "part" hash [b] metal false))]
    (is (faces/layer? updated))
    (is (= {a [1 0 0] b metal} (:faces updated)))
    (is (= old (:layer (faces/stroke updated "part" hash [b] metal true))))
    (is (= :invalid-material (:error (faces/stroke old "part" hash [b] (assoc metal :roughness ##NaN) false))))))
