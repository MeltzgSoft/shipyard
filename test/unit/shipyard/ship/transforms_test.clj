(ns shipyard.ship.transforms-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.ship.transforms :as ship]
            [shipyard.paint.job :as job]
            [shipyard.scheme.material :as material]))

(deftest sparse-paint-and-live-fleet-inheritance
  (let [red (assoc material/neutral :base [1 0 0]) blue (assoc material/neutral :base [0 0 1])
        scheme {:scheme/id (random-uuid) :scheme/name "Fleet" :scheme/layers {"Primary" blue}}
        vessel {:ship/id (random-uuid) :ship/name "Resolute" :ship/class (random-uuid)
                :ship/scheme (:scheme/id scheme) :ship/paint {:paint/instances {[] {:part-id "hull" :material red}}}}
        profile (job/editor-record vessel scheme)]
    (is (ship/valid? vessel))
    (is (= red (material/resolve-material profile [] "hull" :hull)))
    (is (= blue (material/resolve-material profile [[:weapon 0]] "gun" :weapon)))
    (is (= blue (material/resolve-material profile [] "replaced-hull" :hull)) "Replaced parts do not inherit incompatible custom paint")
    (is (= (:ship/paint vessel) (dissoc (job/from-profile profile) :paint/roles)))
    (is (nil? (:paint/layers (job/from-profile profile))) "Inherited palette values never become custom overrides")
    (is (not (ship/valid? (assoc vessel :ship/class nil))))
    (is (not (ship/valid? (assoc vessel :ship/paint {:paint/instances {[] {:part-id "hull" :material {:base [2 0 0]}}}}))))))
