(ns shipyard.unit.fleet.export-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.fleet.export :as export]))

(deftest csv-field-test
  (testing "CSV escapes commas, quotes and embedded line breaks"
    (is (= "simple" (export/csv-field "simple")))
    (is (= "\"a,b\"" (export/csv-field "a,b")))
    (is (= "\"a\"\"b\"" (export/csv-field "a\"b")))
    (is (= "\"a\nb\"" (export/csv-field "a\nb")))))

(deftest manifest-test
  (testing "a row names the source and total print quantity"
    (is (= "path,variant,quantity\r\na/unsupported.stl,unsupported,3\r\n"
           (export/manifest [{:path "a/unsupported.stl" :variant :unsupported :quantity 3}]))))
  (testing "empty fleet retains a parseable manifest header"
    (is (= "path,variant,quantity\r\n" (export/manifest [])))))
