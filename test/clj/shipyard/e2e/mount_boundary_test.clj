(ns shipyard.e2e.mount-boundary-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.picked-face-test :as pick]
            [shipyard.e2e.support :as s]
            [shipyard.mount-boundary-fixture :as parts])
  (:import [java.nio.file Files]))

(deftest select-save-edit-and-reload-untrimmed-mount-faces
  (s/assert-bundle!)
  (let [started (fixture/start! true parts/build! (fn [_]))
        sys (:system started) cat (:shipyard.catalog/db sys) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url sys))
      (doseq [[id label expected outline?] [[parts/thin-id "Thin turret" 4 true]
                                            [parts/duplicate-id "Duplicated turret" 8 false]]]
        (let [source (.toPath (io/file (str (:root started)) id "unsupported.stl"))
              before (Files/readAllBytes source)
              mounts #(-> (catalog/part-context! cat id) :part :part/mounts)]
          (s/open-part! driver label) (s/await-part driver id)
          (s/click! driver "[data-detail-tab=mounts]")
          (pick/pick! driver [2 1 0])
          (is (s/wait-until #(= expected (get-in (s/stats driver) [:preview :triangles]))))
          (testing "save needs no trimming and no generated cut"
            (s/select-option! driver "select[name=kind]" "plug")
            (s/fill-and-blur! driver "input[name=mount-id]" "seat")
            (s/click! driver ".mount-wizard__actions button[value=create]")
            (is (s/wait-until #(= 1 (count (mounts)))))
            (is (s/wait-until #(nil? (:preview (s/stats driver)))))
            (is (= outline? (boolean (seq (:mount/outline (first (mounts))))))))
          (let [selected (get-in (first (mounts)) [:mount/facet :indices])]
            (is (= expected (count selected)))
            (s/go! driver (s/base-url sys))
            (s/open-part! driver label) (s/await-part driver id)
            (is (s/wait-until #(= selected (get-in (s/stats driver) [:interfaces :items 0 :facet-indices]))))
            (s/click! driver "[data-detail-tab=mounts]")
            (s/click! driver "form:has(input[name=mount-id][value='seat']) button:text-is('Edit')")
            (is (s/wait-until #(= selected (get-in (s/stats driver) [:preview :facet-indices]))))
            (s/screenshot-el! driver "#viewport" (io/file (str "/tmp/shipyard-untrimmed-" expected ".png")))
            (s/click! driver ".mount-wizard__actions button[value=update]")
            (is (s/wait-until #(nil? (:preview (s/stats driver)))))
            (is (= selected (get-in (first (mounts)) [:mount/facet :indices]))))
          (is (java.util.Arrays/equals before (Files/readAllBytes source)))))
      (finally (s/quit! driver) (fixture/stop! started)))))
