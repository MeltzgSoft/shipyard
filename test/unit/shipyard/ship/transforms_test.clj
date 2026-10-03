(ns shipyard.ship.transforms-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.paint.job :as job]
            [shipyard.scheme.material :as material]))

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
