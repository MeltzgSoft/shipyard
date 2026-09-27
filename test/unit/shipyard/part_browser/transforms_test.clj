(ns shipyard.part-browser.transforms-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.part-browser.transforms :as t]
            [shipyard.part-browser.thumbnail :as thumbnail]))

(deftest edits-test
  (let [parts [{:part/id "fixed/id" :part/name "Old Hull"}]
        edit #(t/edits parts (merge {"field" "name" "operation" "set" "value" "New"} %))]
    (is (= [{:id "fixed/id" :attribute :part/name-override :value "New"}] (:changes (edit {}))))
    (is (= "NewOld Hull" (-> (edit {"operation" "prefix"}) :changes first :value)))
    (is (= "Old HullNew" (-> (edit {"operation" "suffix"}) :changes first :value)))
    (is (= "New Hull" (-> (edit {"operation" "replace" "find" "Old"}) :changes first :value)))
    (is (:error (edit {"operation" "replace"})))
    (is (:error (edit {"value" " "})))
    (is (:error (edit {"field" "role" "value" "invalid"})))
    (is (= :prow (-> (edit {"field" "role" "value" "prow"}) :changes first :value)))
    (is (:error (t/edits [] {})))))

(deftest triangles-test
  (let [mesh {:positions [0 0 0 1 0 0 0 1 0] :indices [0 1 2]}
        result (thumbnail/triangles mesh nil)]
    (is (= 1 (count result)))
    (is (every? (fn [[x y]] (and (<= 0 x 128) (<= 0 y 88))) (:points (first result))))))
