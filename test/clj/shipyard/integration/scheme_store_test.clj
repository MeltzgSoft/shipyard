(ns shipyard.integration.scheme-store-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [shipyard.scheme.db :as db]
            [shipyard.scheme.transforms-test :refer [record group-record]]))

(deftest atomic-roundtrip-and-failures
  (let [dir (fs/create-temp-dir) file (fs/path dir "schemes.edn") store (db/open! file)]
    (try
      (is (not (fs/exists? file)))
      (is (= record (:scheme (db/put! store record :create))))
      (is (= (db/snapshot! store) (db/snapshot! (db/open! file))))
      (testing "concurrent creates preserve independent identities"
        (let [records (mapv #(assoc record :scheme/id (random-uuid) :scheme/name (str %)) (range 8))]
          (is (every? :scheme (mapv deref (mapv #(future (db/put! store % :create)) records))))
          (db/put! store (assoc record :scheme/name "Edited") :update)
          (is (= 9 (count (:schemes (db/snapshot! (db/open! file))))))
          (doseq [r records] (is (= r (get-in (db/snapshot! store) [:schemes (:scheme/id r)]))))))
      (testing "directory targets cannot report success or publish memory"
        (let [blocked (fs/create-dirs (fs/path dir "blocked"))
              before (db/snapshot! store) bytes (slurp (fs/file file))]
          (is (= :store-write-failed (:error (db/put! (assoc store :file blocked) record :update))))
          (is (empty? (fs/list-dir blocked)))
          (is (= before (db/snapshot! store)))
          (is (= bytes (slurp (fs/file file))))))
      (finally (fs/delete-tree dir)))))

(deftest invalid-store-preserved
  (let [dir (fs/create-temp-dir) file (fs/file dir "schemes.edn")]
    (try
      (doseq [text ["{" "nil" "{:version 2 :schemes {}}" "{:version 1 :schemes {}} {}"
                    "{:version 1 :schemes {nil {}}}"]]
        (spit file text)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Restore a valid backup" (db/open! file)))
        (is (= text (slurp file))))
      (finally (fs/delete-tree dir)))))

(deftest group-roundtrip-and-invalid-write
  (let [dir (fs/create-temp-dir) file (fs/path dir "schemes.edn") store (db/open! file)
        grouped (assoc record :scheme/groups [group-record])]
    (try
      (is (= grouped (:scheme (db/put! store grouped :create))))
      (is (= grouped (get-in (db/snapshot! (db/open! file)) [:schemes (:scheme/id record)])))
      (let [before (slurp (str file))]
        (is (:error (db/put! store (assoc grouped :scheme/groups [group-record group-record]) :update)))
        (is (= before (slurp (str file)))))
      (finally (fs/delete-tree dir)))))
