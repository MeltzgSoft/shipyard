(ns shipyard.regions.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.regions.model :as model]))
(def mesh (apply str (repeat 64 "a")))
(def face (apply str (repeat 72 "0")))
(deftest region-validation
  (is (model/valid? (model/empty-regions mesh)))
  (is (not (model/valid? (assoc (model/empty-regions mesh) :layers ["Secondary" "Primary"])))))
(deftest region-changes
  (let [empty (model/empty-regions mesh)
        added (:regions (model/change empty mesh 0 "add" nil "Trim" nil))
        painted (:regions (model/change added mesh 1 "assign" "Trim" nil [face]))
        extended (:regions (model/change painted mesh 2 "add" nil "Running Lights" nil))
        renamed (:regions (model/change painted mesh 2 "rename" "Trim" "Engines" nil))]
    (is (= ["Primary" "Secondary" "Trim"] (:layers added)))
    (is (= "Trim" (get-in painted [:faces face])))
    (is (= (:faces painted) (:faces extended)))
    (is (= "Engines" (get-in renamed [:faces face])))
    (is (empty? (:faces (:regions (model/change renamed mesh 3 "delete" "Engines" nil nil)))))
    (is (empty? (:faces (:regions (model/change painted mesh 2 "assign" "Primary" nil [face])))))
    (testing "stale, invalid and protected data never writes"
      (doseq [[revision action layer name keys] [[1 "assign" "Secondary" nil [face]] [0 "add" nil " " nil]
                                                 [0 "add" nil "Primary" nil] [0 "delete" "Primary" nil nil]
                                                 [0 "rename" "Secondary" "Trim" nil] [0 "assign" "Missing" nil [face]]
                                                 [0 "assign" "Secondary" nil ["bad"]]]]
        (is (:error (model/change empty mesh revision action layer name keys))))
      (is (:error (model/change empty (apply str (repeat 64 "b")) 0 "assign" "Secondary" nil [face]))))
    (is (= "Secondary" (last (:layers (:regions (model/change painted mesh 2 "reset" nil nil nil))))))))
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
  (let [empty (model/empty-regions mesh)
        result (:regions (model/change empty mesh 0 "assign" "Trim" nil [face] ["Trim" "Engines"]))]
    (is (= ["Primary" "Secondary" "Trim"] (:layers result)))
    (is (= {face "Trim"} (:faces result)))
    (is (= 1 (:revision result)))
    (is (model/valid? result))
    (is (:error (model/change empty mesh 0 "assign" "Missing" nil [face] ["Trim"])))
    (is (:error (model/change empty mesh 0 "delete" "Trim" nil nil ["Trim"])))
    (is (:error (model/change empty mesh 1 "assign" "Trim" nil [face] ["Trim"])))))
