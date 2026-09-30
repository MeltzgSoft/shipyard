(ns shipyard.regions.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.regions.model :as model]))
(def mesh (apply str (repeat 64 "a")))
(def face (apply str (repeat 72 "0")))

(deftest region-changes
  (let [empty (model/empty-regions mesh)
        painted (:regions (model/change empty mesh 0 "assign" "Secondary" nil [face]))]
    (is (= "Secondary" (get-in painted [:faces face])))
    (is (empty? (:faces (:regions (model/change painted mesh 1 "assign" "Primary" nil [face])))))
    (testing "stale, invalid and registry-only operations never write"
      (doseq [[revision action layer keys] [[1 "assign" "Secondary" [face]] [0 "add" nil nil]
                                            [0 "delete" "Primary" nil] [0 "rename" "Secondary" nil]
                                            [0 "assign" "Missing" [face]] [0 "assign" "Secondary" ["bad"]]]]
        (is (:error (model/change empty mesh revision action layer nil keys))))
      (is (:error (model/change empty (apply str (repeat 64 "b")) 0 "assign" "Secondary" nil [face]))))
    (is (= (assoc empty :revision 2) (:regions (model/change painted mesh 1 "reset" nil nil nil))))))
(deftest region-preview-materials
  (is (= #{"Primary" "Secondary"} (set (keys (model/preview-materials (model/empty-regions mesh)))))))

(deftest remove-shared-layer
  (let [regions {:mesh-key mesh :revision 3 :layers ["Primary" "Secondary" "Trim"] :faces {face "Trim"}}]
    (is (= {:mesh-key mesh :revision 4 :layers ["Primary" "Secondary"] :faces {}}
           (model/without-layer regions "Trim")))
    (doseq [name ["Primary" "Secondary" "Missing"]]
      (is (= regions (model/without-layer regions name))))
    (is (nil? (model/without-layer nil "Trim")))))

(deftest assigning-a-shared-layer
  (let [layer "layer:00000000-0000-0000-0000-000000000001"
        empty (model/empty-regions mesh)
        result (:regions (model/change empty mesh 0 "assign" layer nil [face] [layer "layer:00000000-0000-0000-0000-000000000002"]))]
    (is (= ["Primary" "Secondary" layer] (:layers result)))
    (is (= {face layer} (:faces result)))
    (is (= 1 (:revision result)))
    (is (:error (model/change empty mesh 0 "assign" "Missing" nil [face] [layer])))
    (is (:error (model/change empty mesh 0 "delete" layer nil nil [layer])))
    (is (:error (model/change empty mesh 1 "assign" layer nil [face] [layer])))))
