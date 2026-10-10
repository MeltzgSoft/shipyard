(ns shipyard.regions.surface-preparation-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [shipyard.regions.surface-preparation :as preparation]))

(deftest compact-component-lookup
  (testing "Typed-array projection expands each component once without triangle analysis"
    (let [words (js/Uint32Array. #js [3 2 0 0 1 0 2 3 0 1 2])
          groups (preparation/decode (.-buffer words))]
      (is (= #{0 1} (preparation/expand groups [0 1])))
      (is (= #{0 1 2} (preparation/expand groups [0 2])))
      (is (= #{} (preparation/expand groups []))))))
