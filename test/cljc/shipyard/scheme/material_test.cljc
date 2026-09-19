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
