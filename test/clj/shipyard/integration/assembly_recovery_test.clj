(ns shipyard.integration.assembly-recovery-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.assembly.db :as assembly]
            [shipyard.assembly.scene :as scene]
            [shipyard.integration.assembly-test :refer [envelope]]
            [shipyard.workspace.db :as workspace]))

(deftest missed-resets-are-retransmitted-until-acknowledged
  (let [started (fixture/start!)
        handler (:handler started)
        request! (fn [method path params ack]
                   (let [response (handler (-> (mock/request method path params)
                                               (mock/header "X-Shipyard-Scene-Sequence" (str ack))))]
                     (is (= 200 (:status response)))
                     (envelope response)))]
    (try
      (testing "losing the initial resume still initializes the client on its next poll"
        (let [initial (request! :get "/assembly" nil -1)
              recovered (request! :get "/assembly?poll=1" nil -1)]
          (is (= :reset (:op (first (:commands recovered)))))
          (is (= :assembly (:mode (scene/accept-event scene/empty-state recovered))))
          (is (> (:sequence recovered) (:sequence initial)))))
      (testing "a missed reset to the same hull invalidates the previous geometry token"
        (request! :post "/assembly/hull" {:revision "0" :part-id (:hull fixture/ids)} -1)
        (let [deadline (+ (System/currentTimeMillis) 30000)
              ready (loop []
                      (let [event (request! :get "/assembly" nil -1)]
                        (if (or (some #(= :set (:op %)) (:commands event))
                                (> (System/currentTimeMillis) deadline))
                          event
                          (do (Thread/sleep 25) (recur)))))
              before (scene/accept-event scene/empty-state ready)
              reset (request! :post "/assembly/hull" {:revision "1" :discard-revision "1" :part-id (:hull fixture/ids)} (:sequence ready))
              first-retry (request! :get "/assembly?poll=1" nil (:sequence ready))
              second-retry (request! :get "/assembly?poll=1" nil (:sequence ready))
              recovered (scene/accept-event before first-retry)
              acknowledged (request! :get "/assembly?poll=1" nil (:sequence first-retry))]
          (is (some? (get-in before [:slots [] :token])))
          (is (= :reset (:op (first (:commands reset)))))
          (is (= :reset (:op (first (:commands first-retry)))))
          (is (= :reset (:op (first (:commands second-retry)))))
          (is (not= (get-in before [:slots [] :token]) (get-in recovered [:slots [] :token])))
          (is (= :snapshot (:op (first (:commands acknowledged))))
              "acknowledging an earlier retransmission clears the intentional reset boundary")))
      (testing "the standalone assembly component retains the same boundary without a workspace"
        (let [system (:system started)
              deps {:catalog (:shipyard.catalog/db system) :library (:shipyard.library/index system)
                    :cache (:shipyard.mesh/cache system) :jobs (:shipyard.http/jobs system)
                    :assembly (:shipyard.assembly/db system)}
              initial (:event (assembly/request! deps nil {:resume? true}))
              retry (:event (binding [workspace/*scene-sequence* (dec (:sequence initial))]
                              (assembly/request! deps nil {})))
              recovered (:event (binding [workspace/*scene-sequence* (:sequence initial)]
                                  (assembly/request! deps nil {})))]
          (is (= :reset (:op (first (:commands retry)))))
          (is (= :snapshot (:op (first (:commands recovered)))))))
      (finally (fixture/stop! started)))))
