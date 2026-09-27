(ns shipyard.paint.delta-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.paint.delta :as delta]))

(deftest changes-round-trip
  (doseq [before [nil {} {"a" "red"} {"a" "red" "b" "blue"}]
          after [{} {"b" "green"} {"a" "red" "b" "blue"}]]
    (is (= after (delta/apply-patch before (delta/between before after))))))

(deftest dense-maps-have-small-patches
  (let [before (zipmap (map str (range 10000)) (repeat "red"))
        after (-> before (dissoc "1") (assoc "2" "green"))
        patch (delta/between before after)]
    (is (= {:set {"2" "green"} :remove ["1"]} patch))
    (is (= after (delta/apply-patch before patch)))
    (is (= {:replace {}} (delta/between before {})))))
