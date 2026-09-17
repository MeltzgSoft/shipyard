(ns shipyard.integration.loadout-store-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [shipyard.loadout.db :as db]
            [shipyard.loadout.transforms-test :refer [record]]))

(deftest durable-round-trip
  (let [dir (fs/create-temp-dir) file (fs/path dir "loadouts.edn") store (db/open! file)]
    (try
      (is (not (fs/exists? file)) "opening an empty store does not write")
      (is (= record (:loadout (db/put! store record :create))))
      (is (= (db/snapshot! store) (db/snapshot! (db/open! file))))
      (let [copy (assoc record :loadout/id (random-uuid))]
        (is (= copy (:loadout (db/put! store copy :create))))
        (db/put! store (assoc record :loadout/name "Edited") :update)
        (let [records (:loadouts (db/snapshot! (db/open! file)))]
          (is (= copy (get records (:loadout/id copy))))
          (is (= "Edited" (:loadout/name (get records (:loadout/id record)))))))
      (finally (fs/delete-tree dir)))))

(deftest malformed-store-is-never-overwritten
  (let [dir (fs/create-temp-dir) file (fs/file dir "loadouts.edn")]
    (try
      (doseq [content ["{" "{:version 2 :loadouts {}}" "{:version 1 :loadouts {}} {}" "nil"]]
        (spit file content)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Restore a valid backup" (db/open! file)))
        (is (= content (slurp file))))
      (finally (fs/delete-tree dir)))))

(deftest failed-write-preserves-memory-and-data
  (let [dir (fs/create-temp-dir) file (fs/path dir "loadouts.edn") store (db/open! file)]
    (try
      (db/put! store record :create)
      (let [before (db/snapshot! store) bytes (slurp (fs/file file))
            blocked (fs/path dir "blocked")]
        (spit (fs/file blocked) "not a directory")
        (is (= :store-write-failed (:error (db/put! (assoc store :file (fs/path blocked "file"))
                                                    (assoc record :loadout/name "New") :update))))
        (is (= before (db/snapshot! store)))
        (is (= bytes (slurp (fs/file file)))))
      (testing "serialized concurrent creates survive reload"
        (let [records (repeatedly 8 #(assoc record :loadout/id (random-uuid)))]
          (dorun (map deref (mapv #(future (db/put! store % :create)) records)))
          (is (= 9 (count (:loadouts (db/snapshot! (db/open! file))))))))
      (finally (fs/delete-tree dir)))))

(deftest durable-deletion-and-write-failure
  (let [dir (fs/create-temp-dir) file (fs/path dir "loadouts.edn") store (db/open! file)
        id (:loadout/id record) other (assoc record :loadout/id (random-uuid))]
    (try
      (doseq [value [record other]] (db/put! store value :create))
      (let [before (db/snapshot! store) bytes (slurp (fs/file file)) blocked (fs/path dir "blocked")]
        (spit (fs/file blocked) "not a directory")
        (is (= :store-write-failed (:error (db/delete! (assoc store :file (fs/path blocked "file")) id))))
        (is (= before (db/snapshot! store)))
        (is (= bytes (slurp (fs/file file))))
        (is (= before (db/snapshot! (db/open! file)))))
      (is (= {:deleted id} (db/delete! store id)))
      (is (= {(:loadout/id other) other} (:loadouts (db/snapshot! (db/open! file)))))
      (let [bytes (slurp (fs/file file))]
        (is (= :missing-loadout (:error (db/delete! store id))))
        (is (= bytes (slurp (fs/file file)))))
      (db/delete! store (:loadout/id other))
      (is (empty? (:loadouts (db/snapshot! (db/open! file)))))
      (finally (fs/delete-tree dir)))))
