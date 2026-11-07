(ns shipyard.export.transforms-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
      (is (thrown? clojure.lang.ExceptionInfo (t/plan [{:loadout/name "Missing hull"}] parts sources t/default-priority)))
      (is (thrown? clojure.lang.ExceptionInfo (t/plan classes {} sources t/default-priority)))
      (is (thrown? clojure.lang.ExceptionInfo (t/plan classes parts [] t/default-priority))))))

(deftest source-path
  (is (= "Bundle/Hull/supported.stl" (t/source-path "Bundle/Hull/supported.stl")))
  (doseq [path ["../x.stl" "/x.stl" "a//x.stl" "a/./x.stl" "C:/x.stl" "a\\x.stl" "//server/share/x.stl"]]
    (is (thrown? clojure.lang.ExceptionInfo (t/source-path path)))))

(deftest priority-and-move
  (is (t/priority? t/default-priority))
  (is (not (t/priority? [:supported :supported :unsupported])))
  (is (= [:unsupported :unsupported-pitted :supported] (t/move t/default-priority :unsupported -1)))
  (is (= t/default-priority (t/move t/default-priority :supported 1))))

(deftest ship-directories-are-flat-complete-and-collision-safe
  (let [ship {:loadout/id 1 :loadout/name "../Print/ship" :loadout/hull "h"
              :loadout/slots {[[:a 0]] "g" [[:a 1]] "g"} :loadout/extras {"g" 3}}
        parts {"h" {:part/name "Part"} "g" {:part/name "part"}}
        sources [{:owner "h" :variant :unsupported :path "h/unsupported.stl"}
                 {:owner "g" :variant :unsupported :path "g/unsupported.stl"}]
        files (t/plan [ship (assoc ship :loadout/id 2 :loadout/extras {})] parts sources t/default-priority)]
    (is (= [5 1 2 1] (mapv :quantity files)))
    (is (= 2 (count (set (map :ship-directory files)))))
    (is (= 4 (count (set (map (comp str/lower-case :archive-path) files)))))
    (is (every? #(= 2 (count (str/split (:archive-path %) #"/"))) files))
    (is (every? #(not (str/includes? (:archive-path %) "..")) files))
    (is (= files (t/plan (reverse [ship (assoc ship :loadout/id 2 :loadout/extras {})]) parts sources t/default-priority)))))
