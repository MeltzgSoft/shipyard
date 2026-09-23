(ns shipyard.paint.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly.model-test :as fixture]
            [shipyard.paint.transforms :as t]
            [shipyard.scheme.material :as m]))

(def draft {:hull "hull" :assignments {[[:weapon 0]] "weapon" [[:weapon 1]] "weapon"}})

(deftest targets-test
  (testing "root and repeated instances remain distinct alongside role defaults"
    (let [targets (t/targets fixture/database draft)]
      (is (= 7 (count targets)))
      (is (= ["[]" "[[:weapon 0]]" "[[:weapon 1]]" "layer/Primary" "layer/Secondary" "role/hull" "role/weapon"] (mapv :key targets)))
      (is (empty? (t/targets fixture/database {:assignments {}}))))))

(deftest target-material-test
  (testing "role and instance selections resolve their own effective material"
    (let [red (assoc m/neutral :base [1 0 0])
          scheme {:scheme/roles {:weapon red}}]
      (is (= red (t/target-material scheme {:role :weapon})))
      (is (= red (t/target-material scheme {:path [[:weapon 0]] :part-id "weapon" :role :weapon})))
      (is (= m/neutral (t/target-material scheme {:role :hull}))))))

(deftest affected-paths-test
  (testing "changing a role leaves explicit instance overrides intact"
    (let [targets (t/targets fixture/database draft)
          scheme {:scheme/instances {[[:weapon 0]] {:part-id "weapon" :material m/neutral}}}]
      (is (= [[[:weapon 1]]] (t/affected-paths scheme targets {:role :weapon})))
      (is (= [[]] (t/affected-paths scheme targets {:path []}))))))

(deftest color-hex-test
  (testing "sRGB swatch representation"
    (is (= "#ff0080" (t/color-hex [1 0 0.5])))))

(deftest parse-material-test
  (testing "finite and bounded input"
    (let [input {"base" "#ff0080" "metalness" "0.5" "roughness" "0.6"}]
      (is (= [1.0 0.0 (/ 128.0 255)] (:base (t/parse-material input))))
      (doseq [[k v] [["base" "bad"] ["metalness" "NaN"] ["roughness" "1.5"] ["metalness" nil]]]
        (is (nil? (t/parse-material (assoc input k v))))))))

(deftest edit-record-test
  (testing "updates one instance without changing role or other copies"
    (let [record {:scheme/roles {:weapon m/neutral}}
          target {:path [[:weapon 0]] :part-id "weapon"}
          changed (:scheme (t/edit-record record target m/neutral false))]
      (is (= {:part-id "weapon" :material m/neutral} (get-in changed [:scheme/instances [[:weapon 0]]])))
      (is (= (:scheme/roles record) (:scheme/roles changed)))
      (is (empty? (:scheme/instances (:scheme (t/edit-record changed target nil true)))))
      (is (= :invalid-material (:error (t/edit-record record target {} false))))
      (is (= :missing-target (:error (t/edit-record record nil m/neutral false)))))))

(deftest group-target-preview-precedence
  (let [gid #uuid "dc57ee7b-1a06-43e8-a86b-06d7c36cdcfb"
        record {:scheme/groups [{:group/id gid :group/name "Guns" :group/order 0
                                 :group/members [{:path [[:weapon 0]] :part-id "weapon"}
                                                 {:path [[:weapon 1]] :part-id "weapon"}]}]
                :scheme/instances {[[:weapon 1]] {:part-id "weapon" :material m/neutral}}}
        targets (t/targets fixture/database draft record)
        group (first (filter :group-id targets))]
    (is (= (str "group/" gid) (:key group)))
    (is (= [[[:weapon 0]]] (t/affected-paths record targets group)))
    (is (= [[[:weapon 0]]] (t/affected-paths record targets {:role :weapon})))
    (let [changed (:scheme (t/edit-record record group m/neutral false))]
      (is (= [] (t/affected-paths changed targets {:role :weapon})))
      (is (= m/neutral (t/target-material changed group))))))

(deftest parse-detail-test
  (testing "legacy clients inherit finish; new clients submit a complete bounded finish"
    (is (= [1.0 0.0 0.0] (t/parse-detail {"color" "#ff0000"})))
    (let [params {"color" "#ff0000" "metalness" "1" "roughness" "0.15"}]
      (is (= {:base [1.0 0.0 0.0] :metalness 1.0 :roughness 0.15} (t/parse-detail params)))
      (is (nil? (t/parse-detail (dissoc params "roughness"))))
      (doseq [bad ["NaN" "Infinity" "-0.1" "1.1" "" nil]]
        (is (nil? (t/parse-detail (assoc params "roughness" bad))))))))

(deftest shared-layer-targets-without-an-instance
  (let [database (assoc fixture/database :registry
                        {:version 1 :revision 0 :deleted #{}
                         :layers {"layer:00000000-0000-0000-0000-000000000001" {:name "Trim" :preview-name "Trim"}}})
        targets (t/targets database {:hull "hull" :assignments {}} {:scheme/layers {}})]
    (is (some #(= "Trim" (:label %)) targets))
    (is (not-any? #(= "weapon" (:part-id %)) targets))))

(deftest deleted-types-do-not-return-from-saved-palette-colors
  (is (not-any? #(= "Trim" (:label %))
                (t/targets fixture/database draft {:scheme/layers {"Trim" m/neutral}}))))
