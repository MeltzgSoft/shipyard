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

(def sample-entries
  {"a" {:key "a" :sha "original" :variant :unsupported :chain ["Fleet.zip" "Cruiser/Hull.stl"]}
   "b" {:key "b" :sha "supports" :variant :supported :chain ["Fleet.zip" "Cruiser/Hull_Supported.stl"]}})

(deftest inferred-entries-test
  (testing "matching versions and identical repeated downloads share a row"
    (let [entries (t/inferred-entries (assoc sample-entries "c" (assoc (get sample-entries "b") :key "c")))]
      (is (= #{"a"} (set (map :group (vals entries)))))
      (is (= 3 (count entries)))))
  (testing "different geometry competing for one variant stays separate"
    (let [entries (t/inferred-entries (assoc sample-entries "c" (assoc (get sample-entries "b") :key "c" :sha "other")))]
      (is (= #{"a" "b" "c"} (set (map :group (vals entries)))))))
  (testing "different inferred names do not pair"
    (is (= 2 (count (set (map :group (vals (t/inferred-entries
                                            (assoc-in sample-entries ["b" :chain] ["Fleet.zip" "Cruiser/Prow_Supported.stl"]))))))))))

(deftest members-test
  (is (= ["a" "b"] (mapv :key (t/members (t/inferred-entries sample-entries) "a"))))
  (is (empty? (t/members (t/inferred-entries sample-entries) "missing"))))

(deftest preview-entry-test
  (is (= "a" (:key (t/preview-entry (vals sample-entries)))))
  (is (nil? (t/preview-entry [(get sample-entries "b")])))
  (is (nil? (t/preview-entry [(get sample-entries "a") (assoc (get sample-entries "a") :sha "other")]))))

(deftest review-parts-test
  (let [entries (t/inferred-entries sample-entries)
        reviewed (assoc (t/infer "a" (get-in sample-entries ["a" :chain]))
                        :part/name "Reviewed hull" :part/orientation [0 1 0 0])]
    (is (= ["Reviewed hull" #{:unsupported :supported} true [0 1 0 0]]
           ((juxt :part/name :part/variants :part/renderable :part/orientation)
            (first (t/review-parts entries entries {"a" reviewed})))))
    (testing "reassigning the unsupported source invalidates its old orientation"
      (is (nil? (:part/orientation (first (t/review-parts (t/assign-variant entries "b" :unsupported)
                                                          entries {"a" reviewed}))))))))

(deftest group-selection-test
  (let [entries (into {} (map (fn [[id file]] [id (assoc file :group id)])) sample-entries)
        parts (into {} (map (fn [[id file]] [id (t/infer id (:chain file))])) entries)
        result (t/group-selection entries parts ["a" "b"] "My hull")]
    (is (= [] (:selected result)))
    (is (= #{"a"} (set (map :group (vals (:entries result))))))
    (is (= "My hull" (get-in result [:labels "a" :part/name])))
    (is (thrown? clojure.lang.ExceptionInfo (t/group-selection entries parts ["a"] nil)))
    (is (thrown? clojure.lang.ExceptionInfo (t/group-selection entries parts ["a" "missing"] nil)))))

(deftest split-group-test
  (let [entries (t/inferred-entries sample-entries)
        parts (into {} (map (juxt :part/id identity)) (t/review-parts entries {} {}))
        split (t/split-group entries parts "a")
        rows (t/review-parts (:entries split) entries (:labels split))]
    (is (= ["a" "b"] (:selected split)))
    (is (= 2 (count (set (map :part/name rows)))) "split rows have separate destinations")
    (is (= 2 (count (t/plan rows (:entries split)))))
    (is (thrown? clojure.lang.ExceptionInfo (t/split-group entries parts "missing")))))

(deftest assign-variant-test
  (let [entries (t/inferred-entries sample-entries)
        swapped (t/assign-variant entries "b" :unsupported)]
    (is (= [:supported :unsupported] (mapv #(get-in swapped [% :variant]) ["a" "b"])))
    (is (= entries (t/assign-variant swapped "b" :supported)))
    (is (thrown? clojure.lang.ExceptionInfo (t/assign-variant entries "missing" :supported)))
    (is (thrown? clojure.lang.ExceptionInfo (t/assign-variant entries "a" :invalid)))))

(deftest plan-test
  (let [entries (t/inferred-entries sample-entries)
        parts (t/review-parts entries {} {})]
    (testing "supported and original files share a part folder with distinct names"
      (is (= #{"Fleet/Cruiser/Hull/unsupported.stl" "Fleet/Cruiser/Hull/supported.stl"}
             (set (map :path (t/plan parts entries))))))
    (testing "identical repeated files within a row share one destination"
      (is (= 2 (count (t/plan parts (assoc entries "c" (assoc (get entries "b") :key "c")))))))
    (testing "competing content is never silently overwritten"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Assign a different"
                            (t/plan parts (assoc-in entries ["b" :variant] :unsupported)))))
    (testing "separate rows cannot silently regroup during publication"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Separate rows"
                            (t/plan [(first parts) (assoc (first parts) :part/id "b")]
                                    (assoc-in entries ["b" :group] "b")))))))
