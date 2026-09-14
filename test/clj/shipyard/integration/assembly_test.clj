(ns shipyard.integration.assembly-test
  (:require [clojure.xml :as xml]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.assembly.db :as assembly]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]))

(def ^:dynamic *fixture* nil)

(use-fixtures :once
  (fn [run]
    (let [started (fixture/start!)]
      (try (binding [*fixture* started] (run))
           (finally (fixture/stop! started))))))

(defn post! [path params]
  ((:handler *fixture*) (mock/request :post path params)))

(defn envelope [response]
  (let [field (re-find #"<input[^>]*data-assembly-event=[^>]* />" (:body response))]
    (with-open [in (java.io.ByteArrayInputStream. (.getBytes field "UTF-8"))]
      (-> (xml/parse in) :attrs :data-assembly-event edn/read-string))))

(deftest draft-http-workflow
  (testing "the response includes available sources for server-rendered choices"
    (let [system (:system *fixture*)
          deps {:catalog (:shipyard.catalog/db system) :library (:shipyard.library/index system)
                :cache (:shipyard.mesh/cache system) :jobs (:shipyard.http/jobs system)
                :assembly (:shipyard.assembly/db system)}]
      (is (contains? (:available (assembly/request! deps nil {})) (:hull fixture/ids)))))
  (testing "select, fill capacity twice and add a nested turret through the real handler"
    (let [selected (post! "/assembly/hull" {:revision "0" :part-id (:hull fixture/ids)})
          first-slot (post! "/assembly/assign" {:revision "1" :slot "[[:weapon 0]]" :part-id (:weapon fixture/ids)})
          second-slot (post! "/assembly/assign" {:revision "2" :slot "[[:weapon 1]]" :part-id (:weapon fixture/ids)})
          nested (post! "/assembly/assign" {:revision "3" :slot "[[:weapon 0] [:turret 0]]" :part-id (:turret fixture/ids)})]
      (doseq [response [selected first-slot second-slot nested]] (is (= 200 (:status response))))
      (is (= [1 2 3 4] (mapv (comp :revision envelope) [selected first-slot second-slot nested])))))
  (testing "stale revisions, incompatible roles and malicious transport shapes are rejected"
    (is (= 409 (:status (post! "/assembly/reset" {:revision "0"}))))
    (is (= 422 (:status (post! "/assembly/assign" {:revision "4" :slot "[[:weapon 1]]" :part-id (:prow fixture/ids)}))))
    (is (= 409 (:status (post! "/assembly/assign" {:revision "4" :slot "[[:missing 0]]" :part-id (:weapon fixture/ids)}))))
    (is (= 400 (:status (post! "/assembly/reset" {:revision "no"}))))
    (is (= 400 (:status (post! "/assembly/assign" {:revision "4" :slot "garbage" :part-id (:weapon fixture/ids)})))))
  (testing "replacement prunes descendants, clear/reset preserve exact revisions"
    (let [replacement (post! "/assembly/assign" {:revision "4" :slot "[[:weapon 0]]" :part-id (:weapon-alt fixture/ids)
                                                 :matrix "this is ignored"})
          cleared (post! "/assembly/clear" {:revision "5" :slot "[[:weapon 1]]"})
          reset (post! "/assembly/reset" {:revision "6"})]
      (is (= 200 (:status replacement)))
      (is (some #(= {:op :remove :slot [[:weapon 0] [:turret 0]]} %) (:commands (envelope replacement))))
      (is (= 200 (:status cleared)))
      (is (= [{:op :reset}] (:commands (envelope reset))))
      (is (= 7 (:revision (envelope reset))))))
  (testing "browse and mount routes remain available after resetting the draft"
    (is (= 200 (:status ((:handler *fixture*) (mock/request :get "/library")))))
    (is (= 400 (:status (post! "/facet" {})))))
  (testing "background readiness provides server matrices without changing draft revision"
    (is (= 200 (:status (post! "/assembly/hull" {:revision "7" :part-id (:hull fixture/ids)}))))
    (let [deadline (+ (System/currentTimeMillis) 30000)
          ready (loop []
                  (let [response ((:handler *fixture*) (mock/request :get "/assembly?poll=1"))
                        event (envelope response)]
                    (if (or (some #(= :set (:op %)) (:commands event))
                            (> (System/currentTimeMillis) deadline))
                      event
                      (do (Thread/sleep 25) (recur)))))]
      (is (= 8 (:revision ready)))
      (is (= 16 (count (:matrix (first (filter #(= :set (:op %)) (:commands ready)))))))))
  (testing "missing source and unauthored candidates are recoverable"
    (is (= 422 (:status (post! "/assembly/assign" {:revision "8" :slot "[[:weapon 0]]" :part-id (:hint fixture/ids)}))))
    (fs/delete (fs/path (:root *fixture*) (:weapon fixture/ids) "unsupported.stl"))
    (is (= 422 (:status (post! "/assembly/assign" {:revision "8" :slot "[[:weapon 0]]" :part-id (:weapon fixture/ids)})))))
  (testing "relocation remains blocked until an explicit new hull or reset"
    (let [library (get-in *fixture* [:system :shipyard.library/index])
          catalog (get-in *fixture* [:system :shipyard.catalog/db])
          other (str (fs/create-dirs (fs/path (:temp *fixture*) "other")))]
      (index/set-root! library other)
      (catalog/reingest! catalog [] other)
      (dotimes [_ 2]
        (is (= 409 (:status ((:handler *fixture*) (mock/request :get "/assembly"))))))
      (is (= 200 (:status (post! "/assembly/reset" {:revision "8"})))))))
