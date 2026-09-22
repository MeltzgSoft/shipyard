(ns shipyard.paint.groups-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.paint.groups :as groups]
            [shipyard.scheme.transforms :as schema]))

(def a #uuid "8102e06a-0c4a-4c11-914f-0614170871db")
(def b #uuid "24d87c7d-ab3c-4e37-830e-738ec8416551")
(def member {:path [] :part-id "hull"})
(def record {:scheme/id a :scheme/name "Scheme" :scheme/roles {}})

(deftest members-test
  (let [targets [(assoc member :key "[]") {:key "role/hull" :role :hull}]]
    (is (= [member] (groups/members targets "[]")))
    (is (= [member] (groups/members targets ["[]" "[]"])))
    (is (nil? (groups/members targets ["role/hull"])))
    (is (nil? (groups/members targets ["gone"])))
    (is (= [] (groups/members targets nil)))))

(deftest ordered-and-normalize-test
  (is (= [0 1] (mapv :group/order (groups/normalize [{:group/order 9} {:group/order 2}]))))
  (is (= [2 9] (mapv :group/order (groups/ordered {:scheme/groups [{:group/order 9} {:group/order 2}]})))))

(deftest change-test
  (let [created (:scheme (groups/change record :create a "One" [member] nil))
        two (:scheme (groups/change created :create b "Two" [member] nil))
        moved (:scheme (groups/change two :order b nil nil "up"))
        deleted (:scheme (groups/change moved :delete a nil nil nil))]
    (is (schema/scheme? two))
    (is (= [b a] (mapv :group/id (:scheme/groups moved))))
    (is (= [0] (mapv :group/order (:scheme/groups deleted))))
    (is (= moved (:scheme (groups/change moved :order b nil nil "up"))))
    (is (= "Renamed" (-> (groups/change created :rename a "Renamed" nil nil) :scheme :scheme/groups (first) :group/name)))
    (is (= [] (-> (groups/change created :members a nil [] nil) :scheme :scheme/groups (first) :group/members)))
    (is (:error (groups/change record :create a "Empty" [] nil)))
    (is (:error (groups/change created :create a "Duplicate" [member] nil)))
    (is (:error (groups/change created :order b nil nil "up")))
    (is (:error (groups/change created :rename a " " nil nil)))))
