(ns shipyard.mount.facet-recovery-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.mount.facet-recovery :as recovery]))

(def mount {:mount/id :port-1 :mount/pos [1.0 0.0 0.0]
            :mount/axis [1.0 0.0 0.0] :mount/roll [0.0 0.0 1.0]})

(deftest apply-matches-test
  (let [mesh-key "mesh"
        matched {:port-1 {:mount mount :indices [2 3]}}]
    (testing "a matching untouched durable mount receives mesh-scoped indices"
      (is (= {:mesh-key mesh-key :indices [2 3]}
             (get-in (first (recovery/apply-matches mesh-key [mount] matched))
                     [:mount/facet]))))
    (testing "a concurrent authoring edit wins over recovery"
      (let [updated (first (recovery/apply-matches mesh-key
                                                   [(assoc mount :mount/roll [0.0 1.0 0.0])]
                                                   matched))]
        (is (= [0.0 1.0 0.0] (:mount/roll updated)))
        (is (nil? (:mount/facet updated)))))))
