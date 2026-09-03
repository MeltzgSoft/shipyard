(ns shipyard.interface-colors-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.interface-colors :as interface-colors]))

(deftest type-of-test
  (testing "plugs are their own interface type"
    (is (= :plug (interface-colors/type-of {:mount/kind :plug}))))
  (testing "single-role sockets use the accepted role"
    (is (= :weapon (interface-colors/type-of {:mount/kind :socket
                                              :mount/accepts #{:weapon}}))))
  (testing "multi-role sockets are grouped deliberately"
    (is (= :multi (interface-colors/type-of {:mount/kind :socket
                                             :mount/accepts #{:weapon :turret}})))))

(deftest legend-items-test
  (let [items (interface-colors/legend-items [{:mount/kind :socket
                                               :mount/accepts #{:weapon}}
                                              {:mount/kind :socket
                                               :mount/accepts #{:weapon}}
                                              {:mount/kind :plug}])]
    (is (= ["plug" "weapon socket"] (mapv :label items)))
    (is (= ["#69d2c0" "#ff7a90"] (mapv :color items)))))
