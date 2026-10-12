(ns shipyard.integration.fleet-export-http-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.fleet.db :as fleets]
            [shipyard.loadout.db :as classes]
            [shipyard.loadout-fixture :as lf])
  (:import [java.util.zip ZipFile]))

(defn- request! [handler method url params]
  (handler (mock/header (mock/request method url params) "HX-Request" "true")))

(deftest export-http-captures-selected-fleet-and-preserves-earlier-bundles
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        facade {:store (:shipyard.store/db sys) :catalog (:shipyard.catalog/db sys)}
        class {:loadout/id (random-uuid) :loadout/name "Print class"
               :loadout/hull (:hull fixture/ids) :loadout/slots lf/assignments}
        directory (str (fs/path (:temp started) "exports"))]
    (try
      (classes/put! (:shipyard.loadout/db sys) class :create)
      (request! handler :get "/workspace/settings" {})
      (is (= 200 (:status (request! handler :post "/settings/export-root" {"export-root" directory}))))
      (let [fleet (:fleet (fleets/create! facade "Print fleet")) id (:fleet/id fleet)]
        (fleets/put! facade (assoc fleet :fleet/entries {(:loadout/id class) 2}
                                   :fleet/extras {(:weapon fixture/ids) 3}) :update)
        (request! handler :get "/workspace/fleets" {})
        (request! handler :post "/fleets/open" {"id" (str id)})
        (testing "a foreign fleet form cannot export the selected list"
          (is (= 409 (:status (request! handler :post "/fleets/export" {"id" (str (random-uuid))}))))
          (is (empty? (fs/glob directory "*.zip"))))
        (testing "each complete bundle contains original sources and one manifest row per file"
          (is (= 200 (:status (request! handler :post "/fleets/export" {"id" (str id)}))))
          (let [path (first (fs/glob directory "*.zip"))]
            (is (some? path))
            (with-open [zip (ZipFile. (fs/file path))]
              (let [weapon-path (str (:weapon fixture/ids) "/unsupported.stl")
                    manifest (slurp (.getInputStream zip (.getEntry zip "index.csv")))]
                (is (str/includes? manifest (str weapon-path ",unsupported,11")))
                (is (str/includes? manifest (str (:hull fixture/ids) "/unsupported.stl,unsupported,2")))
                (is (= (seq (java.nio.file.Files/readAllBytes (fs/path (:root started) weapon-path)))
                       (seq (.readAllBytes (.getInputStream zip (.getEntry zip weapon-path))))))))))
        (is (= 200 (:status (request! handler :post "/fleets/export" {"id" (str id)}))))
        (is (= 2 (count (fs/glob directory "*.zip"))))
        (testing "source failure publishes nothing and preserves completed archives"
          (let [before (into {} (map (fn [path] [(str path) (seq (java.nio.file.Files/readAllBytes path))])) (fs/glob directory "*.zip"))]
            (fs/delete (fs/path (:root started) (:weapon fixture/ids) "unsupported.stl"))
            (is (= 422 (:status (request! handler :post "/fleets/export" {"id" (str id)}))))
            (is (= before (into {} (map (fn [path] [(str path) (seq (java.nio.file.Files/readAllBytes path))])) (fs/glob directory "*.zip"))))
            (is (empty? (fs/glob directory ".shipyard-fleet-*"))))))
      (finally (fixture/stop! started)))))
