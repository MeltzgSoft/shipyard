(ns shipyard.part-browser.navigation-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.part-browser.navigation :as navigation]))

(deftest complete-filtered-order-and-boundaries
  (let [ids (mapv str (range 65))]
    (is (= {:previous nil :next "1"} (navigation/neighbors ids "0")))
    (is (= {:previous "48" :next "50"} (navigation/neighbors ids "49")))
    (is (= {:previous "63" :next nil} (navigation/neighbors ids "64")))
    (is (nil? (navigation/neighbors ids "missing")))
    (is (nil? (navigation/neighbors [] "0")))
    (is (= {:previous nil :next nil} (navigation/neighbors ["one"] "one")))))
