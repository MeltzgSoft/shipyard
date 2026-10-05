(ns shipyard.integration.mount-alignment-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.persistence-fixture :as persisted]
            [shipyard.http.urls :as urls]
            [shipyard.library.index :as index]))

(deftest mount-axis-http-and-store-round-trip
  (let [started (fixture/start!) cat (get-in started [:system :shipyard.catalog/db])
        handler (:handler started) id (:weapon fixture/ids)
        params {:part-id id :mount-id "plug" :original-mount-id "plug" :action "update" :kind "plug"
                :frame (pr-str fixture/plug) :alignment-axis "vertical-negative"}
        post! (fn [values] (handler (mock/request :post "/mounts" values)))
        mounts #(get-in (persisted/catalog! cat) [:parts id :part/mounts])
        plug #(first (filter (comp #{:plug} :mount/kind) (mounts)))]
    (try
      (let [library (get-in started [:system :shipyard.library/index])
            deadline (+ (System/currentTimeMillis) 30000)]
        (loop []
          (let [response (handler (mock/request :get (urls/part-url id)))]
            (when-not (and (index/mesh-key! library id) (str/includes? (:body response) "detail--ready"))
              (when (> (System/currentTimeMillis) deadline) (throw (ex-info "Part preparation timed out" {})))
              (Thread/sleep 25) (recur)))))
      (testing "alignment is saved in the shared mount entity and survives reopening"
        (is (= 200 (:status (post! params))))
        (is (= :vertical-negative (:mount/alignment-axis (plug))))
        (let [body (:body (handler (mock/request :post "/mounts/edit" {:part-id id :mount-id "plug"})))]
          (is (str/includes? body "Alignment axis"))
          (is (re-find #"<option[^>]*selected[^>]*value=\"vertical-negative\"" body))))
      (testing "invalid input preserves the full prior durable mount set"
        (let [before (mounts)]
          (let [response (post! (assoc params :alignment-axis "diagonal"))]
            (is (= 200 (:status response)))
            (is (str/includes? (:body response) "Choose None or a signed Horizontal/Vertical direction")))
          (is (= before (mounts)))
          (is (thrown? Exception (catalog/save-mounts! cat id [(assoc fixture/plug :mount/alignment-axis :bad)])))
          (is (= before (mounts)))))
      (testing "None retracts the optional attribute rather than storing a fake axis"
        (is (= 200 (:status (post! (assoc params :alignment-axis "none")))))
        (is (not (contains? (plug) :mount/alignment-axis))))
      (finally (fixture/stop! started)))))
