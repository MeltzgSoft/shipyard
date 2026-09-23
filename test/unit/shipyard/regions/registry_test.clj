(ns shipyard.regions.registry-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.regions.registry :as registry]))

(def id "layer:11111111-1111-4111-8111-111111111111")
(def other "layer:22222222-2222-4222-8222-222222222222")

(deftest shared-entity-lifecycle
  (let [added (:registry (registry/change registry/empty-registry 0 "add" nil "Trim" id))
        renamed (:registry (registry/change added 1 "rename" id "Accent" nil))
        deleted (:registry (registry/change renamed 2 "delete" id nil nil))]
    (is (registry/valid? added))
    (is (= ["Primary" "Secondary" id] (registry/ids added)))
    (is (= {:name "Accent" :preview-name "Trim"} (get-in renamed [:layers id])))
    (is (= ["Primary" "Secondary"] (registry/ids deleted)))
    (is (= #{id} (:deleted deleted)))
    (is (registry/valid? deleted))
    (doseq [[rev action layer name new-id] [[0 "rename" id "Other" nil]
                                            [1 "rename" "Primary" "Other" nil]
                                            [1 "delete" "Secondary" nil nil]
                                            [1 "add" nil "Trim" other]
                                            [1 "rename" id "Secondary" nil]
                                            [1 "add" nil " " other]]]
      (is (:error (registry/change added rev action layer name new-id))))))

(deftest discovered-definitions-respect-shared-edits
  (let [region {:layer-definitions {id {:name "Trim" :preview-name "Trim"}}}
        found (registry/discover registry/empty-registry [region])
        renamed (:registry (registry/change found 0 "rename" id "Accent" nil))
        deleted (:registry (registry/change found 0 "delete" id nil nil))]
    (is (= "Accent" (get-in (registry/discover renamed [region]) [:layers id :name])))
    (is (empty? (:layers (registry/discover deleted [region])))))
  (is (not (registry/valid? {:version 1 :revision 0 :layers {"Trim" {}} :deleted #{}}))))
