(ns shipyard.vocabulary.transforms-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.vocabulary.transforms :as t]))

(deftest role
  (is (= :sensor-array (t/role " Sensor Array ")))
  (is (= :weapon_2 (t/role "weapon_2")))
  (doseq [value [nil "" "bad/role" "turret-or-antenna" (apply str (repeat 81 "a"))]]
    (is (nil? (t/role value)))))

(deftest entry
  (is (= {:field :bundle :value "New Fleet"} (t/entry "bundle" " New Fleet ")))
  (is (= {:field :class :value "Carrier"} (t/entry "class" "Carrier")))
  (is (= {:field :role :value "sensor-array"} (t/entry "role" "Sensor Array")))
  (doseq [[field value] [["missing" "Fleet"] ["class" " "] ["bundle" "../fleet"] ["role" "bad/role"]]]
    (is (:error (t/entry field value)))))
