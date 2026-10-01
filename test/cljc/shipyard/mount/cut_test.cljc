(ns shipyard.mount.cut-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.mount.cut :as cut]))

(def frame {:mount/pos [0.0 0.0 1.0] :mount/axis [0.0 0.0 1.0] :mount/roll [1.0 0.0 0.0]})

(deftest request-test
  (testing "opt in; kind defaults depend on mount kind"
    (is (= {} (cut/request {} :socket)))
    (is (= :pit (cut/default-kind :socket)))
    (is (= :recess (cut/default-kind :plug)))
    (is (= {:cut {:kind :pit :depth 1.0 :diameter 2.0}}
           (cut/request {"create-pitted" "true" "cut-depth" "1" "cut-diameter" "2"} :socket))))
  (testing "reject non-finite and invalid physical measurements"
    (doseq [depth ["0" "-1" "NaN" "Infinity" ""]]
      (is (:error (cut/request {"create-pitted" "true" "cut-depth" depth "cut-diameter" "2"} :socket))))
    (is (:error (cut/request {"create-pitted" "true" "cut-kind" "recess" "cut-depth" "1" "cut-border" "-1"} :plug)))
    (is (:error (cut/request {"create-pitted" "true" "cut-kind" "recess" "cut-depth" "1" "cut-border" ""} :plug)))
    (is (= {:cut {:kind :recess :depth 1.0 :border 0.0}}
           (cut/request {"create-pitted" "true" "cut-kind" "recess" "cut-depth" "1" "cut-border" "0"} :plug)))))

(deftest outline-test
  (testing "ignore triangulation edges, retain wound face perimeter"
    (is (= [[[0 0 0] [4 0 0] [4 2 0] [0 2 0]]]
           (cut/outline [[[0 0 0] [4 0 0] [0 2 0]] [[4 0 0] [4 2 0] [0 2 0]]]))))
  (testing "empty and open triangle sets cannot supply an outline"
    (is (nil? (cut/outline [])))))

(deftest project-and-point-test
  (testing "source coordinates and face coordinates round trip"
    (is (= [[2.0 3.0]] (cut/project frame [[2.0 3.0 1.0]])))
    (is (= [2.0 3.0 0.5] (cut/point frame [2.0 3.0] -0.5)))))

(deftest pit-rings-test
  (testing "each socket section gets its own center"
    (let [mount (assoc frame :mount/cut {:kind :pit :depth 0.5 :diameter 1.0}
                       :mount/capacity 2 :mount/split {:direction :vertical :bounds [[-2.0 -1.0] [2.0 1.0]]})
          profiles (cut/pit-rings mount)]
      (is (= [[-1.0 0.0 1.0] [1.0 0.0 1.0]] (mapv #(get-in % [:frame :mount/pos]) profiles)))
      (is (= [0.5 0.0] (-> profiles first :rings first first))))))

(deftest wire-lines-test
  (testing "top, floor and walls represent true depth"
    (let [lines (cut/wire-lines frame [[[-1 -1] [1 -1] [1 1] [-1 1]]] 0.5)]
      (is (= 12 (count lines)))
      (is (= #{0.5 1.0} (set (map last (apply concat lines))))))))
