(ns shipyard.integration.orient-save-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.bulk-orientation.save-state :as saves]
            [shipyard.catalog.db :as catalog]
            [shipyard.part.orientation :as orientation]))

(defn post! [handler request activation poses]
  (handler (-> (mock/request :post "/orient/save" {"request" (str request) "orientations" (pr-str poses)})
               (mock/header "x-shipyard-workspace" "orient")
               (mock/header "x-shipyard-activation" (str activation)))))

(deftest ordered-and-partial-saves-use-real-persistence
  (let [started (fixture/start!) id (:prow fixture/ids) handler (:handler started)
        q45 (orientation/from-euler-degrees 45 0 0) q90 (orientation/from-euler-degrees 90 0 0)
        current #(-> (:system started) :shipyard.catalog/db (catalog/snapshot!) (catalog/part id) :part/orientation)]
    (try
      (is (= 200 (:status (handler (-> (mock/request :get "/workspace/orient")
                                       (mock/header "HX-Request" "true"))))))
      (let [result (post! handler 2 1 {id q45 "missing" q90})]
        (is (= 422 (:status result)))
        (is (str/includes? (:body result) "Saved 1. Failed: missing"))
        (is (str/includes? (:body result) ":request 2"))
        (is (saves/same-pose? q45 (current))))
      (is (= 204 (:status (post! handler 1 1 {id q90}))))
      (is (= 204 (:status (post! handler 2 1 {id q90}))))
      (is (saves/same-pose? q45 (current)))
      (is (= 200 (:status (post! handler 3 1 {id q90}))))
      (is (saves/same-pose? q90 (current)))
      (is (= 204 (:status (post! handler 4 0 {id q45}))))
      (is (saves/same-pose? q90 (current)))
      (finally (fixture/stop! started)))))
