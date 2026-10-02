(ns shipyard.jobs.transforms-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.jobs.transforms :as t]))

(deftest explicit-limits-have-bounded-defaults
  (is (= {:threads 2 :queue-size 32} (t/limits {})))
  (is (= {:threads 2 :queue-size 32} (t/limits {:threads nil :queue-size nil})))
  (is (= {:threads 3 :queue-size 8} (t/limits {:threads 3 :queue-size 8})))
  (doseq [field [:threads :queue-size] value [false 0 -1 :auto "2" 2.5 2147483648]]
    (is (thrown? clojure.lang.ExceptionInfo (t/limits {field value})))))
