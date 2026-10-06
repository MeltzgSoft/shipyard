(ns shipyard.ship.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.ship.transforms :as t]
            [shipyard.paint.job :as job]
            [shipyard.scheme.material :as material]))

(def record {:ship/id #uuid "00000000-0000-0000-0000-000000000001"
             :ship/name "Resolute" :ship/class #uuid "00000000-0000-0000-0000-000000000002"
             :ship/paint {}})

(deftest put-record-test
  (testing "validation and conflicts depend only on the target identity"
    (is (= {:ship record} (t/put-record false record :create)))
    (is (= {:ship record} (t/put-record true record :update)))
    (is (= {:error :id-exists} (t/put-record true record :create)))
    (is (= {:error :missing-ship} (t/put-record false record :update)))
    (is (= {:error :invalid-operation} (t/put-record true record :delete)))
    (is (= {:error :invalid-ship} (t/put-record true {} :update)))))

(deftest delete-record-test
  (testing "deletion preserves its public result"
    (is (= {:deleted (:ship/id record)} (t/delete-record true (:ship/id record))))
    (is (= {:error :missing-ship} (t/delete-record false (:ship/id record))))))

(deftest sparse-paint-and-live-fleet-inheritance
  (let [red (assoc material/neutral :base [1 0 0]) blue (assoc material/neutral :base [0 0 1])
        scheme {:scheme/id (random-uuid) :scheme/name "Fleet" :scheme/layers {"Primary" blue}}
        vessel {:ship/id (random-uuid) :ship/name "Resolute" :ship/class (random-uuid)
                :ship/scheme (:scheme/id scheme) :ship/paint {:paint/details {[] {:part-id "hull" :mesh-key "source" :faces {"face" red}}}}}
        profile (job/editor-record vessel scheme)]
    (is (= red (get-in profile [:scheme/details [] :faces "face"])))
    (is (= blue (material/resolve-material profile)))
    (is (= (:ship/paint vessel) (job/from-profile profile)))
    (is (nil? (:paint/layers (job/from-profile profile))) "Inherited palette values never become custom overrides")))
