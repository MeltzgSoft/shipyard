(ns shipyard.integration.emission-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.paint.emission-job :as emission]
            [shipyard.preparation :as preparation]
            [shipyard.integration.thumbnail-cache-test :as previews]))

(deftest source-moments-survive-appearance-revisions
  (let [started (fixture/start!) sys (:system started)
        service (:shipyard.preparation/service sys) library (:shipyard.library/index sys)
        id (:weapon fixture/ids)
        key (:mesh-key (cache/ensure! (:shipyard.mesh/cache sys) (index/fresh-source-file! library id)))]
    (try
      (index/record-mesh-key! library id key nil)
      (let [placement {:part-id id :mesh-key key}
            initial (emission/request! service placement)]
        (previews/await! #(= :ready (:state (preparation/status! service (:resource initial)))))
        (let [source (get-in @(:meshes service) [:entries [:emission-source 1 key] :value])
              face (first (keys source))
              edited (emission/request! service (assoc placement :regions {:mesh-key key :revision 1 :faces {face "Secondary"}}))]
          (previews/await! #(= :ready (:state (preparation/status! service (:resource edited)))))
          (testing "masks get new immutable summaries while source geometry analysis is reused"
            (is (not= (:resource initial) (:resource edited)))
            (is (identical? source (get-in @(:meshes service) [:entries [:emission-source 1 key] :value])))
            (is (= #{"Primary" "Secondary"} (set (map :layer (:value (preparation/status! service (:resource edited))))))))
          (testing "identical masks reuse their prepared resource"
            (is (= (:resource edited)
                   (:resource (emission/request! service (assoc placement :regions {:mesh-key key :revision 1 :faces {face "Secondary"}}))))))
          (testing "source changes invalidate publication and delivery"
            (swap! (:state library) assoc :root "changed-root")
            (is (nil? (preparation/status! service (:resource edited)))))))
      (finally (fixture/stop! started)))))
