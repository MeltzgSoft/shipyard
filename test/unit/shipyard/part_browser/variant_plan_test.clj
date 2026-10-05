(ns shipyard.part-browser.variant-plan-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.part-browser.variant-plan :as plan]))

(def parts {"a" {:part/id "a" :protected? true} "b" {:part/id "b"} "c" {:part/id "c"}})
(def files [{:key "1" :owner "a" :variant :unsupported :path "a/unsupported.stl"}
            {:key "2" :owner "b" :variant :supported :path "b/supported.stl"}
            {:key "3" :owner "c" :variant :unsupported-pitted :path "c/unsupported-pitted.stl"}])

(deftest grouping-preserves-the-unsupported-owner
  (let [result (plan/plan parts files :group {:ids ["b" "a"] :name "Battery"})]
    (is (= [{:key "2" :owner "a" :variant :supported :path "a/supported.stl" :before (second files)}] (:moves result)))
    (is (= {:id "a" :name "Battery"} (:label result)))
    (is (empty? (:selected result)))
    (is (empty? (:changed-source result)))))

(deftest variant-swaps-and-splits
  (let [unprotected (update parts "a" dissoc :protected?)
        grouped [(first files) (assoc (second files) :owner "a" :origin "b")]
        swapped (plan/plan unprotected grouped :variant {:file "2" :variant :unsupported})
        split (plan/plan unprotected grouped :split {:group "a"})]
    (is (= [:supported :unsupported] (mapv :variant (:moves swapped))))
    (is (= #{"a"} (:changed-source swapped)))
    (is (= "b" (:owner (first (:moves split)))))
    (is (= ["a" "b"] (:selected split)))))

(deftest invalid-edits-are-rejected
  (doseq [[ps fs action params]
          [[parts files :group {:ids ["a"]}]
           [parts files :group {:ids ["a" "missing"]}]
           [(assoc-in parts ["b" :protected?] true) files :group {:ids ["a" "b"]}]
           [parts (assoc-in files [1 :variant] :unsupported) :group {:ids ["a" "b"]}]
           [parts [(first files) (assoc (second files) :owner "a")] :variant {:file "2" :variant :unsupported}]
           [parts files :variant {:file "missing" :variant :supported}]]]
    (is (thrown? clojure.lang.ExceptionInfo (plan/plan ps fs action params)))))

(deftest splitting-separates-files-with-the-same-original-owner
  (let [grouped [(first files)
                 (assoc (second files) :owner "a" :origin "b")
                 (assoc (nth files 2) :owner "a" :origin "b")]
        result (plan/plan parts grouped :split {:group "a"})]
    (is (= ["b" "a - unsupported-pitted"] (mapv :owner (:moves result))))
    (is (= 3 (count (:selected result))))))
