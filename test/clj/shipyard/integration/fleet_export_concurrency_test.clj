(ns shipyard.integration.fleet-export-concurrency-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.fleet.db :as fleets]
            [shipyard.loadout.db :as classes])
  (:import [java.util.zip ZipFile]))

(defn- request! [handler method url params]
  (handler (mock/header (mock/request method url params) "HX-Request" "true")))

(deftest durable-writer-waits-until-complete-export-publication
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        facade {:store (:shipyard.store/db sys) :catalog (:shipyard.catalog/db sys)}
        class {:loadout/id (random-uuid) :loadout/name "Snapshot class"
               :loadout/hull (:hull fixture/ids) :loadout/slots {}}
        directory (str (fs/path (:temp started) "exports"))
        copying (promise) resume (promise) writer-started (promise)
        copy-once? (atom false) workers (atom [])
        original-copy! io/copy]
    (try
      (classes/put! (:shipyard.loadout/db sys) class :create)
      (request! handler :get "/workspace/settings" {})
      (is (= 200 (:status (request! handler :post "/settings/export-root" {"export-root" directory}))))
      (let [fleet (:fleet (fleets/create! facade "Snapshot fleet")) id (:fleet/id fleet)
            initial (assoc fleet :fleet/entries {(:loadout/id class) 2})
            updated (assoc initial :fleet/entries {(:loadout/id class) 4})]
        (fleets/put! facade initial :update)
        (request! handler :get "/workspace/fleets" {})
        (request! handler :post "/fleets/open" {"id" (str id)})
        (with-redefs [io/copy (fn [input output]
                                (when (and (instance? java.util.zip.ZipOutputStream output)
                                           (compare-and-set! copy-once? false true))
                                  (deliver copying true)
                                  (when (= ::timeout (deref resume 10000 ::timeout))
                                    (throw (ex-info "Export copy barrier timed out" {}))))
                                (original-copy! input output))]
          (let [exporting (future (request! handler :post "/fleets/export" {"id" (str id)}))]
            (swap! workers conj exporting)
            (is (= true (deref copying 10000 ::timeout)))
            (let [writing (future
                            (deliver writer-started true)
                            (fleets/put! facade updated :update))]
              (swap! workers conj writing)
              (is (= true (deref writer-started 10000 ::timeout)))
              (testing "durable update cannot change references while source copying is active"
                (is (= ::blocked (deref writing 100 ::blocked))))
              (deliver resume true)
              (is (= 200 (:status (deref exporting 10000 {:status ::timeout}))))
              (is (= updated (:fleet (deref writing 10000 {})))))))
        (testing "published manifest retains the captured quantity and later write becomes visible"
          (let [bundles (fs/glob directory "*.zip")]
            (is (= 1 (count bundles)))
            (when-let [path (first bundles)]
              (with-open [zip (ZipFile. (fs/file path))
                          input (.getInputStream zip (.getEntry zip "index.csv"))]
                (let [manifest (slurp input)]
                  (is (str/includes? manifest (str (:hull fixture/ids) "/unsupported.stl,unsupported,2")))
                  (is (not (str/includes? manifest ",unsupported,4")))))))
          (is (= updated (fleets/record! facade id)))
          (is (empty? (fs/glob directory ".shipyard-fleet-*")))))
      (finally
        (deliver resume true)
        ;; Release and join workers before shutting down the real store.
        (doseq [worker @workers]
          (try
            (when (= ::timeout (deref worker 10000 ::timeout))
              (future-cancel worker))
            (catch Exception _ nil)))
        (fixture/stop! started)))))
