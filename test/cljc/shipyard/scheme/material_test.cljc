(ns shipyard.scheme.material-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.scheme.material :as m]))

(deftest resolve-material-test
  (testing "instance identity, role and neutral precedence"
    (let [role (assoc m/neutral :metalness 0.8)
          specific (assoc m/neutral :roughness 0.1)
          scheme {:scheme/roles {:weapon role}
                  :scheme/instances {[[:weapon 0]] {:part-id "gun" :material specific}}}]
      (is (= specific (m/resolve-material scheme [[:weapon 0]] "gun" :weapon)))
      (is (= role (m/resolve-material scheme [[:weapon 1]] "gun" :weapon)))
      (is (= role (m/resolve-material scheme [[:weapon 0]] "replacement" :weapon)))
      (is (= m/neutral (m/resolve-material scheme [] "hull" :hull))))))

(deftest select-scheme-test
  (testing "dangling overrides do not silently select a fleet default"
    (let [schemes {:ship {:scheme/name "Ship"} :fleet {:scheme/name "Fleet"}}]
      (is (= (:ship schemes) (:scheme (m/select-scheme schemes :ship :fleet))))
      (is (= (:fleet schemes) (:scheme (m/select-scheme schemes nil :fleet))))
      (is (:missing? (m/select-scheme schemes :gone :fleet)))
      (is (nil? (:scheme (m/select-scheme schemes :gone :fleet))))
      (is (false? (:missing? (m/select-scheme schemes nil nil)))))))

(deftest srgb->linear-test
  (testing "sRGB transfer curve endpoints and middle"
    (is (= 0.0 (m/srgb->linear 0)))
    (is (= 1.0 (m/srgb->linear 1)))
    (is (< (#?(:clj Math/abs :cljs js/Math.abs) (- 0.21404114 (m/srgb->linear 0.5))) 1.0e-7))))

(deftest group-resolution-and-source
  (let [a {:group/id :a :group/order 1 :group/members [{:path [] :part-id "hull"}]
           :group/material (assoc m/neutral :metalness 0.9)}
        b (assoc a :group/id :b :group/order 0 :group/material (assoc m/neutral :roughness 0.1))
        scheme {:scheme/groups [a b]}]
    (is (= [b a] (m/groups-for scheme [] "hull")))
    (is (= b (m/winning-group scheme [] "hull")))
    (is (= (:group/material b) (m/resolve-material scheme [] "hull" :hull)))
    (is (= [:group :b] (m/material-source scheme [] "hull" :hull)))
    (is (= m/neutral (m/resolve-material scheme [] "replacement" :hull)))
    (is (= [:role :hull] (m/material-source scheme [] "replacement" :hull)))
    (is (= (:group/material a) (m/resolve-material (assoc scheme :scheme/groups [a (dissoc b :group/material)]) [] "hull" :hull)))
    (is (= [:instance []] (m/material-source (assoc scheme :scheme/instances {[] {:part-id "hull" :material m/neutral}}) [] "hull" :hull)))))

(deftest primary-layer-and-overrides
  (let [primary (assoc m/neutral :metalness 1)
        role (assoc m/neutral :roughness 0.1)
        scheme {:scheme/roles {:hull role} :scheme/layers {"Primary" primary}}]
    (is (= primary (m/resolve-material scheme [] "hull" :hull)))
    (is (= [:layer "Primary"] (m/material-source scheme [] "hull" :hull)))
    (is (= role (m/resolve-material (dissoc scheme :scheme/layers) [] "hull" :hull)))
    (is (= role (m/resolve-material (assoc scheme :scheme/instances {[] {:part-id "hull" :material role}}) [] "hull" :hull)))
    (is (= role (m/resolve-material (assoc scheme :scheme/groups [{:group/id :a :group/order 0
                                                                   :group/members [{:path [] :part-id "hull"}]
                                                                   :group/material role}]) [] "hull" :hull)))))
