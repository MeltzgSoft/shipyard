(ns shipyard.importer.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.importer.transforms :as t]))

(deftest infer-test
  (testing "nearest explicit source marker overrides an outer supported archive"
    (let [p (t/infer "id" ["Fleet.zip" "Light Cruisers Supported.zip" "Original Files/Hull.stl"])]
      (is (= ["Fleet" "Light Cruiser" "Hull" :hull #{:unsupported}]
             ((juxt :part/bundle :part/class :part/name :part/role-hint :part/variants) p)))))
  (testing "support markers can be in an ancestor archive or the filename"
    (is (= :supported (t/variant-hint ["Fleet.zip" "Supported.zip" "Hull.stl"])))
    (is (= :unsupported (t/variant-hint ["Supported.zip" "Hull_Unsupported.stl"])))
    (is (= "Hull" (t/part-name ["Fleet.zip" "SUPPORTED_Hull (repaired).stl"]))))
  (testing "canonical variant files retain the part folder name"
    (is (= "Prow" (t/part-name ["Fleet.zip" "Cruiser/Prow/unsupported.stl"]))))
  (testing "unknown classifications remain reviewable"
    (is (nil? (t/class-hint ["Fleet.zip" "Mystery.stl"])))
    (is (= :unknown (:part/role-hint (t/infer "id" ["Fleet.zip" "Mystery.stl"]))))))

(deftest destination-test
  (testing "weapons and turrets use the scanner hierarchy"
    (is (= {:id "Fleet/Cruiser/weapons/turrets/Gun" :path "Fleet/Cruiser/weapons/turrets/Gun/supported.stl"}
           (t/destination {:part/bundle "Fleet" :part/class "Cruiser" :part/name "Gun" :part/role-hint :turret} :supported))))
  (testing "unsafe path segments cannot escape or disappear from scans"
    (doseq [s [".." "a/b" "a\\b" "other" "CON" "bad." ""]]
      (is (not (t/safe-segment? s))))))

(deftest plan-test
  (let [p {:part/id "a" :part/name "Hull" :part/bundle "Fleet" :part/role-hint :hull}
        q (assoc p :part/id "b")
        a {:sha "same" :variant :unsupported}]
    (testing "identical repeated downloads share one destination"
      (is (= 1 (count (t/plan [p q] {"a" a "b" a})))))
    (testing "coalescing identical files retains a reviewed orientation and rejects competing poses"
      (is (= [0 1 0 0] (get-in (first (t/plan [p (assoc q :part/orientation [0 1 0 0])] {"a" a "b" a})) [:part :part/orientation])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reviewed values"
                            (t/plan [(assoc p :part/orientation [1 0 0 0]) (assoc q :part/orientation [0 1 0 0])] {"a" a "b" a}))))
    (testing "different geometry is never silently overwritten"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Different files"
                            (t/plan [p q] {"a" a "b" (assoc a :sha "different")})))))
  (testing "supported and original files share a part folder with distinct names"
    (is (= 2 (count (t/plan [(t/infer "a" ["Fleet.zip" "Hull.stl"])
                             (t/infer "b" ["Fleet.zip" "Hull_Supported.stl"])]
                            {"a" {:sha "a" :variant :unsupported} "b" {:sha "b" :variant :supported}}))))))
