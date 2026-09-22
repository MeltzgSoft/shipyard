(ns shipyard.scheme.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.scheme.transforms :as t]))

(def material {:base [0.2 0.3 0.4] :metalness 0.5 :roughness 0.6 :paint "Blue"})
(def record {:scheme/id #uuid "93c3df7f-4b02-44cd-a4ed-c239f6851a8c" :scheme/name "Navy"
             :scheme/roles {:weapon material}
             :scheme/instances {[] {:part-id "hull" :material material}
                                [[:weapon 0] [:turret 1]] {:part-id "turret" :material material}}})

(deftest unit-number?-test
  (testing "bounded finite values only"
    (doseq [x [0 1 0.5]] (is (t/unit-number? x)))
    (doseq [x [nil "0" -0.1 1.1 ##NaN ##Inf ##-Inf]] (is (not (t/unit-number? x))))))

(deftest material?-test
  (testing "optional free text and complete PBR fields"
    (is (t/material? material))
    (is (t/material? (dissoc material :paint)))
    (doseq [value [(dissoc material :base) (assoc material :base [0 1])
                   (assoc material :base [0 0 ##NaN]) (assoc material :paint 4)
                   (assoc material :extra 4) (assoc material :roughness ##Inf)]]
      (is (not (t/material? value))))))

(deftest instance-entry?-test
  (testing "full paths, root and assigned identity"
    (is (t/instance-entry? [[] {:part-id "hull" :material material}]))
    (is (t/instance-entry? [[[:weapon 1] [:turret 0]] {:part-id "turret" :material material}]))
    (is (not (t/instance-entry? [[[:weapon -1]] {:part-id "x" :material material}])))
    (is (not (t/instance-entry? [[] {:material material}])))))

(deftest scheme?-test
  (testing "records preserve role and instance overrides"
    (is (t/scheme? record))
    (is (t/scheme? (dissoc record :scheme/instances)))
    (doseq [value [(assoc record :scheme/name " ") (assoc record :scheme/id "id")
                   (assoc record :scheme/roles {"weapon" material})
                   (assoc record :scheme/instances {[] {:part-id "x" :material {}}})]]
      (is (not (t/scheme? value))))))

(deftest store?-test
  (testing "version and record identities agree"
    (is (t/store? t/empty-store))
    (is (t/store? {:version 1 :schemes {(:scheme/id record) record}}))
    (is (not (t/store? {:version 2 :schemes {}})))
    (is (not (t/store? {:version 1 :schemes {nil record}})))))

(deftest put-record-test
  (testing "explicit identity and operation"
    (let [store (:store (t/put-record t/empty-store record :create))]
      (is (= record (get-in store [:schemes (:scheme/id record)])))
      (is (= :id-exists (:error (t/put-record store record :create))))
      (is (= :missing-scheme (:error (t/put-record t/empty-store record :update))))
      (is (= :invalid-operation (:error (t/put-record store record :delete))))
      (is (= :invalid-scheme (:error (t/put-record store {} :create))))
      (is (= "Updated" (get-in (t/put-record store (assoc record :scheme/name "Updated") :update)
                               [:scheme :scheme/name]))))))

(def group-record {:group/id #uuid "8621a7ab-c9e7-43a7-a2b9-8e0700589fb1" :group/name "Battery"
                   :group/order 0 :group/members [{:path [[:weapon 0]] :part-id "weapon"}]
                   :group/material material})

(deftest member?-test
  (is (t/member? {:path [] :part-id "hull"}))
  (is (not (t/member? {:path [[:weapon -1]] :part-id "weapon"})))
  (is (not (t/member? {:path []}))))

(deftest group?-test
  (is (t/group? group-record))
  (is (t/group? (dissoc group-record :group/material)))
  (doseq [value [(assoc group-record :group/order -1)
                 (assoc group-record :group/name " ")
                 (assoc group-record :group/material {})
                 (update group-record :group/members #(into % %))]]
    (is (not (t/group? value)))))

(deftest groups?-test
  (is (t/groups? []))
  (is (t/scheme? (assoc record :scheme/groups [group-record])))
  (is (not (t/groups? [group-record group-record])))
  (is (not (t/groups? [group-record (assoc group-record :group/id (random-uuid))])))
  (is (t/groups? [group-record (assoc group-record :group/id (random-uuid) :group/order 1)])))
