(ns shipyard.viewport-loading-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [shipyard.viewport-loading.rules :as rules]))

(deftest brush-workflow-test
  (testing "each brush workflow keeps its existing pending feedback"
    (is (rules/brush-workflow? :browse "regions" false false))
    (is (rules/brush-workflow? :browse "mounts" false true))
    (is (rules/brush-workflow? :ships nil true false)))
  (testing "ordinary mount picking, assembly and other workspaces show loading feedback"
    (is (not (rules/brush-workflow? :browse "mounts" false false)))
    (is (not (rules/brush-workflow? :browse "part" false false)))
    (is (not (rules/brush-workflow? :browse "part" false true)))
    (is (not (rules/brush-workflow? :ships nil false false)))
    (is (not (rules/brush-workflow? :settings "regions" true true)))))
