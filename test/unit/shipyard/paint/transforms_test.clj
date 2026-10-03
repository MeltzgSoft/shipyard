(ns shipyard.paint.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly.model-test :as fixture]
            [shipyard.paint.transforms :as t]))

(def draft {:hull "hull" :assignments {[[:weapon 0]] "weapon" [[:weapon 1]] "weapon"}})

(deftest targets-test
  (testing "root and repeated instances remain distinct for detail masks"
    (let [targets (t/targets fixture/database draft)]
      (is (= 3 (count targets)))
      (is (= ["[]" "[[:weapon 0]]" "[[:weapon 1]]"] (mapv :key targets)))
      (is (empty? (t/targets fixture/database {:assignments {}}))))))

(deftest color-hex-test
  (testing "sRGB swatch representation"
    (is (= "#ff0080" (t/color-hex [1 0 0.5])))))

(deftest parse-material-test
  (testing "finite and bounded input"
    (let [input {"base" "#ff0080" "metalness" "0.5" "roughness" "0.6"}]
      (is (= [1.0 0.0 (/ 128.0 255)] (:base (t/parse-material input))))
      (is (= 0.7 (:glow (t/parse-material (assoc input "glow" "0.7")))))
      (is (not (contains? (t/parse-material input) :glow)) "Glow is optional and defaults to zero")
      (doseq [[k v] [["base" "bad"] ["metalness" "NaN"] ["roughness" "1.5"] ["metalness" nil]
                     ["glow" "NaN"] ["glow" "Infinity"] ["glow" "-0.1"] ["glow" "1.1"] ["glow" ""] ["glow" nil]]]
        (is (nil? (t/parse-material (assoc input k v))))))))

(deftest parse-detail-test
  (testing "strokes require a complete bounded finish"
    (is (nil? (t/parse-detail {"color" "#ff0000"})))
    (let [params {"color" "#ff0000" "metalness" "1" "roughness" "0.15"}]
      (is (= {:base [1.0 0.0 0.0] :metalness 1.0 :roughness 0.15} (t/parse-detail params)))
      (is (= 0.8 (:glow (t/parse-detail (assoc params "glow" "0.8")))))
      (is (nil? (t/parse-detail {"color" "#ff0000" "glow" "0.8"})))
      (is (nil? (t/parse-detail (dissoc params "roughness"))))
      (doseq [bad ["NaN" "Infinity" "-0.1" "1.1" "" nil]]
        (is (nil? (t/parse-detail (assoc params "roughness" bad))))))))
