(ns shipyard.assembly.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly.routes :as routes]))

(deftest slot-string?-test
  (testing "bounded capacity and nested paths"
    (is (routes/slot-string? "[[:weapon 1] [:turret 0]]")))
  (testing "malformed input and extra EDN forms"
    (doseq [value [nil "[]" "[[:weapon -1]]" "[[:weapon 256]]" "[[:weapon 0]] []"
                   "#unknown [1]" "[[weapon 0]]" "[[\"weapon\" 0]]" "broken"]]
      (is (false? (routes/slot-string? value))))))
