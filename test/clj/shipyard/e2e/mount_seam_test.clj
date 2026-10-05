(ns shipyard.e2e.mount-seam-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.picked-face-test :as pick]
            [shipyard.e2e.support :as s]
            [shipyard.mount-seam-fixture :as parts])
  (:import [java.nio.file Files]))

(deftest mirrored-seams-select-one-centered-mount-from-either-side
  (s/assert-bundle!)
  (let [started (fixture/start! true parts/build! (fn [_])) sys (:system started)
        cat (:shipyard.catalog/db sys) driver (s/make-driver)
        source (.toPath (io/file (str (:root started)) parts/id "unsupported.stl"))
        before (Files/readAllBytes source)
        mounts #(-> (catalog/part-context! cat parts/id) :part :part/mounts)]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "Mirrored Hull") (s/await-part driver parts/id)
      (s/click! driver "[data-detail-tab=mounts]")
      (pick/trace-completions! driver)
      (testing "clicking either half highlights the same complete eight-triangle face"
        (pick/pick! driver parts/left-point)
        (pick/reads! driver 1)
        (s/wait-visible! driver ".mount-wizard__form")
        (is (s/wait-until #(= 8 (get-in (s/stats driver) [:preview :triangles]))))
        (let [left (:preview (s/stats driver))]
          (is (= [0 0 0] (:position left)))
          (is (= [0 1 0] (:axis left)))
          (pick/pick! driver parts/right-point)
          (pick/reads! driver 2)
          (is (s/wait-until #(= (:facet-indices left) (get-in (s/stats driver) [:preview :facet-indices]))))
          (is (= (select-keys left [:position :axis :roll])
                 (select-keys (:preview (s/stats driver)) [:position :axis :roll]))))
        (is (empty? (mounts))))
      (testing "a disconnected coplanar surface remains a separate selection"
        (pick/pick! driver parts/island-point)
        (pick/reads! driver 3)
        (is (s/wait-until #(= 2 (get-in (s/stats driver) [:preview :triangles]))))
        (pick/pick! driver parts/left-point)
        (pick/reads! driver 4)
        (is (s/wait-until #(= 8 (get-in (s/stats driver) [:preview :triangles])))))
      (s/select-option! driver ".mount-wizard__form select[name=kind]" "socket")
      (s/select-option! driver ".mount-wizard__form select[name=accepts]" "bridge")
      (s/fill-and-blur! driver ".mount-wizard__form input[name=mount-id]" "bridge-1")
      (let [selected (get-in (s/stats driver) [:preview :facet-indices])]
        (testing "only Save persists the centered frame and exact complete membership"
          (s/click! driver ".mount-wizard__actions button[value=create]")
          (is (s/wait-until #(= 1 (count (mounts)))))
          (is (s/wait-until #(= 8 (get-in (s/stats driver) [:interfaces :items 0 :triangles]))))
          (let [saved (first (mounts))]
            (is (= [0.0 0.0 0.0] (:mount/pos saved)))
            (is (= selected (get-in saved [:mount/facet :indices])))))
        (testing "Edit and reloading retain the whole saved face"
          (s/click! driver "form:has(input[name=mount-id][value='bridge-1']) button:text-is('Edit')")
          (s/wait-visible! driver ".mount-wizard__form")
          (is (s/wait-until #(= selected (get-in (s/stats driver) [:preview :facet-indices]))))
          (s/click! driver ".mount-wizard__actions button[value=update]")
          (is (s/wait-until #(nil? (:preview (s/stats driver)))))
          (s/go! driver (s/base-url sys))
          (s/open-part! driver "Mirrored Hull") (s/await-part driver parts/id)
          (is (= selected (get-in (s/stats driver) [:interfaces :items 0 :facet-indices])))))
      (is (java.util.Arrays/equals ^bytes before ^bytes (Files/readAllBytes source)))
      (s/screenshot-el! driver "#viewport" (io/file "/tmp/shipyard-centered-seam-mount.png"))
      (finally (s/quit! driver) (fixture/stop! started)))))
