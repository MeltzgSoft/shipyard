(ns shipyard.integration.ship-thumbnail-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as classes]))

(deftest preview-validation-and-unavailable-records
  (let [started (fixture/start!) handler (:handler started) sys (:system started)
        id (random-uuid) get! #(handler (mock/request :get %))]
    (try
      (is (= 400 (:status (get! "/ship-thumbnails/class/invalid"))))
      (is (= 400 (:status (get! (str "/ship-thumbnails/unknown/" id)))))
      (is (str/includes? (:body (get! (str "/ship-thumbnails/class/" id))) "No preview"))
      (classes/put! (:shipyard.loadout/db sys)
                    {:loadout/id id :loadout/name "Cruiser" :loadout/hull (:hull lf/draft) :loadout/slots lf/assignments} :create)
      (let [state @(:state (:shipyard.workspace/db sys))
            response (get! (str "/ship-thumbnails/class/" id))]
        (is (= 200 (:status response)))
        (is (or (str/includes? (:body response) "delay:600ms") (str/includes? (:body response) "/thumbnail-images/")))
        (is (= state @(:state (:shipyard.workspace/db sys)))))
      (finally (fixture/stop! started)))))
