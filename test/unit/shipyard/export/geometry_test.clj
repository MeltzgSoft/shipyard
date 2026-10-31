(ns shipyard.export.geometry-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.export.geometry :as g]))

(deftest stand-cuts
  (let [mount {:mount/kind :stand :mount/pos [0.0 0.0 1.0]
               :mount/axis [0.0 0.0 1.0] :mount/roll [1.0 0.0 0.0] :mount/stand-depth 3.0}
        cuts (g/stand-cuts [mount {:mount/kind :socket}] 2.5)]
    (is (= 1 (count cuts)))
    (is (= [0.0 0.0 1.0] (:mount/pos (first cuts))))
    (is (= {:kind :cylinder :depth 3.0 :diameter 2.5} (:mount/cut (first cuts))))
    (is (thrown? clojure.lang.ExceptionInfo (g/stand-cuts [(dissoc mount :mount/stand-depth)] 2.5)))))
