(ns shipyard.regions.migration-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.regions.migration :as migration]
            [shipyard.regions.model :as model]
            [shipyard.regions.registry :as registry]))

(deftest stable-legacy-identities
  (is (= "Primary" (migration/legacy-id "Primary")))
  (is (= "Secondary" (migration/legacy-id "Secondary")))
  (is (registry/id? (migration/legacy-id "Trim")))
  (is (= (migration/legacy-id "引擎") (migration/legacy-id "引擎")))
  (is (not= (migration/legacy-id "Trim") (migration/legacy-id "trim"))))

(deftest region-upgrade
  (let [key (apply str (repeat 72 "0"))
        legacy {:mesh-key (apply str (repeat 64 "a")) :revision 5
                :layers ["Primary" "Secondary" "Trim"] :faces {key "Trim"}}
        value (migration/regions legacy) id (migration/legacy-id "Trim")]
    (is (model/valid? value))
    (is (= 5 (:revision value)))
    (is (= {key id} (:faces value)))
    (is (= {:name "Trim" :preview-name "Trim"} (get-in value [:layer-definitions id])))
    (is (= value (migration/regions value)))
    (is (= (get (model/preview-materials legacy) "Trim") (get (model/preview-materials value) id)))
    (is (nil? (migration/regions nil)))))

(deftest scheme-upgrade
  (let [record {:scheme/name "Old" :scheme/layers {"Primary" :blue "Trim" :gold}}
        value (migration/scheme record)]
    (is (= {:scheme/name "Old" :scheme/layer-ids? true
            :scheme/layers {"Primary" :blue (migration/legacy-id "Trim") :gold}} value))
    (is (= value (migration/scheme value)))
    (is (= {} (migration/scheme {})))))
