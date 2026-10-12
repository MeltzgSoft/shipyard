(ns shipyard.viewport-loading-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [shipyard.viewport-loading.rules :as rules]))

(deftest stroke-request-test
  (testing "painting and erasing strokes retain their own save feedback"
    (is (rules/stroke-request? "/parts/regions/stroke" nil nil))
    (is (rules/stroke-request? "/ships/paint/stroke" nil "paint"))
    (is (rules/stroke-request? "/ships/paint/stroke" nil "erase")))
  (testing "other jobs show loading feedback even in brush tools"
    (doseq [path ["/ships/paint/select" "/ships/tab/paint" "/ships/tab/schemes"
                  "/parts/regions" "/parts/regions/surfaces" "/mounts/preview"]]
      (is (not (rules/stroke-request? path nil nil))))
    (is (not (rules/stroke-request? "/ships/paint/stroke" "undo" "paint")))
    (is (not (rules/stroke-request? "/ships/paint/stroke" "redo" "paint")))
    (is (not (rules/stroke-request? "/ships/paint/stroke" nil "undo")))
    (is (not (rules/stroke-request? "/ships/paint/stroke" nil "redo")))))
