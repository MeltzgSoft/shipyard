(ns shipyard.settings.transforms-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.settings.transforms :as settings]
            [shipyard.vocabulary.management :as management]))

(deftest cut-default-validation
  (let [valid {"pit-depth" "1.25" "pit-diameter" "3" "recess-depth" "2" "recess-border" "0"}]
    (is (= {:values {:pit {:depth 1.25 :diameter 3.0} :recess {:depth 2.0 :border 0.0}}}
           (settings/cut-settings valid)))
    (doseq [value ["0" "-1" "NaN" "Infinity" "" "bad"]]
      (is (:error (settings/cut-settings (assoc valid "pit-depth" value)))))
    (is (:error (settings/cut-settings (assoc valid "recess-border" "-1"))))))

(deftest role-renames-cover-effective-labels-and-socket-acceptance
  (let [parts [{:db/id 1 :part/role-hint :sensor :part/revision 4
                :part/mounts [{:db/id 2 :mount/accepts [:sensor :weapon]}]}
               {:db/id 3 :part/role-hint :sensor :part/role-override :engine :part/revision 2}]
        rows (get (management/entries parts []) :role)
        result (management/plan parts [] :rename "role" "sensor" "Sensor Array")]
    (is (= {:value "sensor" :parts 1 :sockets 1 :builtin? false} (some #(when (= "sensor" (:value %)) %) rows)))
    (is (= :role (:field result)))
    (is (= [{:vocabulary/key [:role "sensor-array"] :vocabulary/field :role :vocabulary/value "sensor-array"}
            {:db/id 1 :part/revision 5 :part/role-override :sensor-array}
            [:db/retract 2 :mount/accepts :sensor] [:db/add 2 :mount/accepts :sensor-array]] (:tx result)))
    (is (:error (management/plan parts [] :delete "role" "sensor" nil)))
    (is (:error (management/plan parts [] :rename "role" "weapon" "cannon")))
    (is (:error (management/plan parts [] :delete "role" "engine" nil)))
    (is (:error (management/plan parts [] :rename "role" "sensor" "engine")))))
