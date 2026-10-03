(ns shipyard.part-browser.transforms-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.part-browser.transforms :as t]
            [shipyard.part-browser.thumbnail :as thumbnail]))

(deftest listed-test
  (is (not (t/listed? {:part/variants [:supported]} false)))
  (is (t/listed? {:part/variants [:supported]} true))
  (is (t/listed? {:part/variants [:unsupported :supported]} false))
  (is (t/listed? {:part/variants [:unsupported-pitted]} false)))

(deftest thumbnail-test
  (is (t/thumbnail? {:part/renderable true} false))
  (is (t/thumbnail? {:part/renderable false :part/source :supported} true))
  (is (not (t/thumbnail? {:part/renderable false :part/source :supported} false)))
  (is (not (t/thumbnail? {:part/renderable false} true))))

(deftest edits-test
  (let [parts [{:part/id "fixed/id" :part/name "Old Hull"}]
        edit #(t/edits parts (merge {"field" "name" "operation" "set" "value" "New"} %))]
    (is (= [{:id "fixed/id" :attribute :part/name-override :value "New"}] (:changes (edit {}))))
    (is (= "NewOld Hull" (-> (edit {"operation" "prefix"}) :changes first :value)))
    (is (= "Old HullNew" (-> (edit {"operation" "suffix"}) :changes first :value)))
    (is (= "New Hull" (-> (edit {"operation" "replace" "find" "Old"}) :changes first :value)))
    (is (:error (edit {"operation" "replace"})))
    (is (:error (edit {"value" " "})))
    (is (:error (edit {"field" "role" "value" "bad/role"})))
    (is (= :prow (-> (edit {"field" "role" "value" "prow"}) :changes first :value)))
    (is (:error (t/edits [] {})))))

(deftest triangles-test
  (let [mesh {:positions [0 0 0 1 0 0 0 1 0] :indices [0 1 2]}
        result (thumbnail/triangles mesh nil)]
    (is (= 1 (count result)))
    (is (every? (fn [[x y]] (and (<= 0 x 128) (<= 0 y 88))) (:points (first result))))))

(deftest row-edits-test
  (let [part {:part/id "a"} params {"name" " Hull " "bundle" "Fleet" "class" "Cruiser" "role" "Sensor Array"}]
    (is (= [{:id "a" :attribute :part/name-override :value "Hull"}
            {:id "a" :attribute :part/bundle-override :value "Fleet"}
            {:id "a" :attribute :part/class-override :value "Cruiser"}
            {:id "a" :attribute :part/role-override :value :sensor-array}]
           (:changes (t/row-edits part params))))
    (is (:error (t/row-edits nil params)))
    (is (:error (t/row-edits part (assoc params "class" " "))))
    (is (:error (t/row-edits part (assoc params "role" "bad/role"))))))
