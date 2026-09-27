(ns shipyard.http.pagination-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.http.pagination :as pagination]))

(deftest bounded-windows
  (doseq [page [nil "garbage" "-4" "0"]]
    (is (= 50 (count (:items (pagination/window (range 120) page))))))
  (is (= (vec (range 50 100)) (:items (pagination/window (range 120) "2"))))
  (is (= (vec (range 100 120)) (:items (pagination/window (range 120) "999"))))
  (is (= {:page 1 :pages 1 :total 0 :items []} (pagination/window [] "3"))))
