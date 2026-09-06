(ns shipyard.http.settings-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.http.settings :as settings]))

(deftest normalise-test
  (testing "paths are trimmed and tildes use the supplied home"
    (is (= "/home/alice/models"
           (settings/normalise "/home/alice" "  ~/models  "))))
  (testing "blank and absent paths become nil"
    (is (nil? (settings/normalise "/home/alice" "   ")))
    (is (nil? (settings/normalise "/home/alice" nil)))))

(deftest path-problem-test
  (testing "a usable directory has no problem"
    (is (nil? (settings/path-problem "/models"
                                     {:exists? true :directory? true :readable? true}))))
  (testing "each failed fact has a specific message"
    (is (= "Enter the folder that holds your STL library."
           (settings/path-problem nil nil)))
    (is (= "No such folder: /models"
           (settings/path-problem "/models" {:exists? false})))
    (is (= "Not a folder: /models"
           (settings/path-problem "/models" {:exists? true :directory? false})))
    (is (= "Shipyard cannot read /models"
           (settings/path-problem "/models"
                                  {:exists? true :directory? true :readable? false})))))
