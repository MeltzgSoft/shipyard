(ns shipyard.assembly.inventory.transforms-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly.inventory.transforms :as t]))

(deftest current-test
  (let [picker {:token "draft" :revision 2 :hull "hull"}
        draft {:revision 2 :hull "hull"}]
    (is (t/current? picker draft "draft"))
    (is (not (t/current? nil draft "draft")))
    (is (not (t/current? picker draft "other")))
    (is (not (t/current? picker (assoc draft :revision 3) "draft")))
    (is (not (t/current? picker (assoc draft :hull "other") "draft")))))

(deftest quantities-test
  (is (= {:quantities {"hidden" 7 "gun" 3}}
         (t/quantities {"hidden" 7 "gun" 1} {"q" "gun" "quantity.gun" "3"})))
  (is (= {:quantities {"hidden" 7}} (t/quantities {"hidden" 7 "gun" 3} {"quantity.gun" "0"})))
  (doseq [value ["" "1.5" "-1" "1000001" ["1" "2"]]]
    (is (= :invalid-quantity (:error (t/quantities {} {"quantity.gun" value}))))))
