(ns shipyard.e2e.mount-seam-settings-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.picked-face-test :as pick]
            [shipyard.e2e.support :as s]
            [shipyard.mount-seam-fixture :as parts]
            [shipyard.settings.db :as settings])
  (:import [java.nio.file Files]))

(deftest settings-control-gap-selection-and-saved-faces-retain-their-tolerance
  (s/assert-bundle!)
  (let [started (fixture/start! true parts/build-gap! #(catalog/save-part-role! % parts/id :hull))
        sys (:system started) cat (:shipyard.catalog/db sys) database (:shipyard.store/db sys)
        driver (s/make-driver)
        source (.toPath (io/file (str (:root started)) parts/id "unsupported.stl"))
        before (Files/readAllBytes source)
        mounts #(-> (catalog/part-context! cat parts/id) :part :part/mounts)
        setting! (fn [value]
                   (s/click! driver "[data-workspace-mode=settings]")
                   (s/wait-visible! driver "#face-selection-settings")
                   (s/fill-and-blur! driver "#face-selection-settings input" value)
                   (s/click! driver "#face-selection-settings button")
                   (s/wait-visible! driver "[role=status]:text-is('Saved mount face selection settings.')")
                   (is (= (Double/parseDouble value) (settings/seam-tolerance! database)))
                   (when (= value "0.005")
                     (s/scroll-into-view! driver "#face-selection-settings button")
                     (s/screenshot-el! driver ".settings-workspace__section:has(#face-selection-settings)"
                                       (io/file "/tmp/shipyard-seam-settings.png")))
                   (s/click! driver "[data-workspace-mode=browse]")
                   (s/await-part driver parts/id)
                   (s/click! driver "[data-detail-tab=mounts]")
                   (is (s/wait-until #(= parts/id (get-in (s/stats driver) [:authoring :part-id])))))]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "Mirrored Hull") (s/await-part driver parts/id)
      (s/click! driver "[data-detail-tab=mounts]")
      (testing "the default bridges a two-micron gap without a per-mount seam control"
        (pick/pick! driver parts/left-point)
        (is (s/wait-until #(= 8 (get-in (s/stats driver) [:preview :triangles]))))
        (is (= 0 (s/count-els driver "input[type=number][name=face-seam]")))
        (is (= 0.005 (settings/seam-tolerance! database))))
      (testing "a global settings change immediately applies to a fresh pick"
        (setting! "0")
        (pick/pick! driver parts/left-point)
        (is (s/wait-until #(= 4 (get-in (s/stats driver) [:preview :triangles]))))
        (setting! "0.005")
        (pick/pick! driver parts/right-point)
        (is (s/wait-until #(= 8 (get-in (s/stats driver) [:preview :triangles])))))
      (testing "preview uses the same gap-aware outline as Save"
        (s/check! driver "input[name=create-pitted]")
        (s/select-option! driver "select[name=cut-kind]" "Pit")
        (is (s/wait-until #(seq (get-in (s/stats driver) [:preview :cuts]))))
        (s/click! driver "input[name=create-pitted]"))
      (s/select-option! driver "select[name=kind]" "socket")
      (s/select-option! driver "select[name=accepts]" "Turret or antenna hardpoint")
      (s/fill-and-blur! driver "input[name=mount-id]" "gap-seat")
      (s/screenshot-el! driver "#viewport" (io/file "/tmp/shipyard-gap-seam.png"))
      (s/click! driver ".mount-wizard__actions button[value=create]")
      (is (s/wait-until #(= 1 (count (mounts)))))
      (is (= 0.005 (get-in (first (mounts)) [:mount/facet :seam-mm])))
      (is (= 1 (count (:mount/outline (first (mounts))))))
      (testing "changing the global default preserves saved membership through edit and Save"
        (setting! "0")
        (s/go! driver (s/base-url sys))
        (s/open-part! driver "Mirrored Hull") (s/await-part driver parts/id)
        (s/click! driver "[data-detail-tab=mounts]")
        (s/click! driver "form:has(input[name=mount-id][value='gap-seat']) button:text-is('Edit')")
        (is (s/wait-until #(= 8 (get-in (s/stats driver) [:preview :triangles]))))
        (s/click! driver ".mount-wizard__actions button[value=update]")
        (is (s/wait-until #(nil? (:preview (s/stats driver)))))
        (is (= 8 (count (get-in (first (mounts)) [:mount/facet :indices])))))
      (is (java.util.Arrays/equals before (Files/readAllBytes source)))
      (finally (s/quit! driver) (fixture/stop! started)))))
