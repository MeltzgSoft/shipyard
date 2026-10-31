(ns shipyard.export.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.export.transforms :as t]))

(deftest inventory
  (is (= {"hull" 2 "gun" 7 "stand" 2}
         (t/inventory [{:loadout/hull "hull" :loadout/slots {[[:a 0]] "gun" [[:a 1]] "gun"}
                        :loadout/extras {"gun" 3 "stand" 2}}
                       {:loadout/hull "hull" :loadout/slots {[[:a 0]] "gun" [[:b 0]] "gun"}}])))
  (is (= {} (t/inventory []))))

(deftest plan
  (let [classes [{:loadout/hull "h" :loadout/slots {[[:a 0]] "g"}}]
        parts {"h" {} "g" {}}
        sources [{:owner "h" :variant :supported :path "h/supported.stl"}
                 {:owner "g" :variant :unsupported :path "g/unsupported.stl"}
                 {:owner "g" :variant :unsupported-pitted :path "g/unsupported-pitted.stl"}]]
    (is (= [:unsupported-pitted :supported] (mapv :variant (t/plan classes parts sources t/default-priority))))
    (is (= [:unsupported :supported] (mapv :variant (t/plan classes parts sources [:unsupported :supported :unsupported-pitted]))))
    (testing "requirements cannot disappear silently"
      (is (thrown? clojure.lang.ExceptionInfo (t/plan classes {} sources t/default-priority)))
      (is (thrown? clojure.lang.ExceptionInfo (t/plan classes parts [] t/default-priority))))))

(deftest archive-path
  (is (= "Bundle/Hull/4x-supported.stl" (t/archive-path "Bundle/Hull/supported.stl" 4)))
  (doseq [path ["../x.stl" "/x.stl" "a//x.stl" "a/./x.stl" "C:/x.stl" "a\\x.stl" "//server/share/x.stl"]]
    (is (thrown? clojure.lang.ExceptionInfo (t/archive-path path 1)))))

(deftest priority-and-move
  (is (t/priority? t/default-priority))
  (is (not (t/priority? [:supported :supported :unsupported])))
  (is (= [:unsupported :unsupported-pitted :supported] (t/move t/default-priority :unsupported -1)))
  (is (= t/default-priority (t/move t/default-priority :supported 1))))
