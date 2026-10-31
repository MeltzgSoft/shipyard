(ns shipyard.integration.ship-export-test
  (:require [babashka.fs :as fs]
            [ring.mock.request :as mock]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.export.db :as export]
            [shipyard.export.geometry :as geometry]
            [shipyard.export.transforms :as t]
            [shipyard.loadout.db :as loadouts]
            [shipyard.loadout-fixture :as lf]
            [shipyard.mesh.stl :as stl]
            [shipyard.mesh.volume :as volume]
            [shipyard.persistence-fixture :as persisted])
  (:import [java.nio.file Files]
           [java.util.zip ZipFile]))

(deftest archives-and-stand-cuts
  (let [started (fixture/start!) deps (lf/deps started) store (:loadouts deps)
        id (random-uuid) id2 (random-uuid)
        hull (:hull fixture/ids) gun (:weapon fixture/ids)
        record {:loadout/id id :loadout/name "Print" :loadout/hull hull
                :loadout/slots {[[:weapon 0]] gun [[:weapon 1]] gun}
                :loadout/extras {gun 3 (:supported fixture/ids) 2}}
        directory (str (fs/path (:temp started) "exports"))
        selection #{[:class id] [:class id2]}
        write! (fn [diameter] (export/export! deps selection t/default-priority directory
                                              (fn [{:keys [database]} entry source] (geometry/bytes! database diameter entry source))))]
    (try
      (is (:loadout (loadouts/put! store record :create)))
      (is (:loadout (loadouts/put! store (assoc record :loadout/id id2 :loadout/name "Second" :loadout/extras {}) :create)))
      (is (= record (get-in (persisted/records! store :loadouts) [:loadouts id])))
      (is (= #{(str hull "/unsupported.stl") (str gun "/unsupported.stl")
               (str (:supported fixture/ids) "/supported.stl")}
             (set (map :path (:files (export/snapshot! deps selection t/default-priority))))))
      (let [path (write! nil)]
        (with-open [zip (ZipFile. path)]
          (is (.getEntry zip (str gun "/7x-unsupported.stl")))
          (is (.getEntry zip (str hull "/2x-unsupported.stl")))
          (is (.getEntry zip (str (:supported fixture/ids) "/2x-supported.stl")))
          (is (.getEntry zip "index.csv")))
        (is (not= path (write! nil))))
      (testing "stand drilling removes the requested cylindrical volume, preserving source bytes"
        (catalog/save-mounts! (:catalog deps) hull
                              [(merge fixture/frame {:mount/id :stand :mount/kind :stand :mount/stand-depth 2.0 :mount/pos [0.0 0.0 2.0]})])
        (let [source (fs/path (:root @(:state (:library deps))) hull "unsupported.stl")
              original (Files/readAllBytes source)
              path (write! 1.5)]
          (with-open [zip (ZipFile. path) input (.getInputStream zip (.getEntry zip (str hull "/2x-unsupported.stl")))]
            (let [data (.readAllBytes input)
                  target (fs/path (:temp started) "cut.stl")]
              (Files/write target data (make-array java.nio.file.OpenOption 0))
              (let [mesh (stl/parse-file! (fs/file target))
                    before (stl/parse-file! (fs/file source))
                    removed (- (volume/signed (:positions before) (:triangle-count before))
                               (volume/signed (:positions mesh) (:triangle-count mesh)))]
                (is (< (Math/abs (- removed (* Math/PI 0.75 0.75 2.0))) 0.01)))))
          (is (= (vec original) (vec (Files/readAllBytes source))))))
      (testing "failure never publishes an archive or leaves temporary work"
        (let [before (set (fs/list-dir directory))]
          (is (thrown? Exception (export/export! deps selection t/default-priority directory
                                                 (fn [_ _ _] (throw (ex-info "fail" {}))))))
          (is (= before (set (fs/list-dir directory))))))
      (finally (fixture/stop! started)))))

(deftest export-http-validation
  (let [started (fixture/start!) handler (:handler started)
        post! (fn [uri params] (handler (mock/request :post uri params)))
        id (random-uuid)]
    (try
      (is (= 400 (:status (post! "/ships/export/selection" {:kind "ship" :id (str id) :selected "true"}))))
      (is (= 400 (:status (post! "/ships/export/selection" {:kind "class" :id "bad" :selected "true"}))))
      (is (= 400 (:status (post! "/ships/export" {:directory "" :move "unsupported:sideways"}))))
      (is (= 200 (:status (post! "/ships/export" {:directory "" :diameter "-1" :action "export"}))))
      (is (str/includes? (:body (post! "/ships/export" {:directory "" :diameter "-1" :action "export"})) "diameter"))
      (finally (fixture/stop! started)))))
