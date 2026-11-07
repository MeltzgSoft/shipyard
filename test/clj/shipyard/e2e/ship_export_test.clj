(ns shipyard.e2e.ship-export-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.loadout.db :as loadouts]
            [shipyard.persistence-fixture :as persisted])
  (:import [java.util.zip ZipFile]))

(deftest select-preview-export-and-inventory-extras
  (s/assert-bundle!)
  (let [started (fixture/start! true (fn [root]
                                       (fixture/library! root)
                                       (fs/copy (fs/path root (:weapon fixture/ids) "unsupported.stl")
                                                (fs/path root (:weapon fixture/ids) "unsupported-pitted.stl"))
                                       root)) sys (:system started) driver (s/make-driver)
        store (:shipyard.loadout/db sys) cat (:shipyard.catalog/db sys)
        id (random-uuid) second-id (random-uuid) hull (:hull fixture/ids) gun (:weapon fixture/ids)
        record {:loadout/id id :loadout/name "Print cruiser" :loadout/hull hull :loadout/slots {}}
        directory (str (fs/path (:temp started) "exports"))]
    (try
      (catalog/save-mounts! cat hull [(merge fixture/frame {:mount/id :stand :mount/kind :stand :mount/stand-depth 2.0 :mount/pos [0.0 0.0 2.0]})])
      (loadouts/put! store record :create)
      (loadouts/put! store (assoc record :loadout/id second-id :loadout/name "Second cruiser" :loadout/extras {gun 2}) :create)
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver "#bulk-orient-results .bulk-orient__row")
      (testing "stand mounts are authored with a depth and survive reloading"
        (s/open-prepared-part! driver sys "hull" hull)
        (s/click! driver "[data-detail-tab=mounts]")
        (s/click! driver "form:has(input[name=mount-id][value='stand']) button:text-is('Edit')")
        (s/wait-visible! driver ".mount-wizard__form")
        (s/select-option! driver ".mount-wizard__form select[name=kind]" "stand")
        (s/fill-and-blur! driver ".mount-wizard__form input[name=stand-depth]" "2.5")
        (s/click! driver ".mount-wizard__actions button[value=update]")
        (is (s/wait-until #(nil? (:preview (s/stats driver)))))
        (is (= 2.5 (get-in (persisted/catalog! cat) [:parts hull :part/mounts 0 :mount/stand-depth]))))
      (s/open-class! driver "Print cruiser")
      (s/wait-visible! driver ".assembly__extras")
      (testing "inventory extras save without entering the viewport"
        (s/click! driver "[data-inventory-open]")
        (s/wait-visible! driver "dialog.inventory-picker")
        (is (= "Synthetic Navy" (s/js driver "() => document.querySelector('#extras-picker [name=bundle]').value")))
        (is (= "Cruiser" (s/js driver "() => document.querySelector('#extras-picker [name=class]').value")))
        (is (zero? (s/count-els driver "#extras-picker details")))
        (s/fill-and-blur! driver (str "[data-inventory-part='" gun "'] input") "3")
        (s/click! driver "#extras-picker button:text-is('Add')")
        (is (s/wait-until #(zero? (s/count-els driver "dialog.inventory-picker"))))
        (s/click! driver "[data-inventory-open]")
        (s/wait-visible! driver "dialog.inventory-picker")
        (is (= "3" (s/js driver (str "() => document.querySelector('[data-inventory-part=\"" gun "\"] input').value"))))
        (s/fill-and-blur! driver (str "[data-inventory-part='" gun "'] input") "99")
        (s/click! driver "#extras-picker button:text-is('Cancel')")
        (is (s/wait-until #(zero? (s/count-els driver "dialog.inventory-picker"))))
        (is (s/wait-until #(= 1 (count (get-in (s/stats driver) [:assembly :slots])))))
        (s/click! driver ".assembly__save button")
        (s/wait-visible! driver "[role=status]:text-is('Class saved.')")
        (is (= {gun 3} (get-in (persisted/records! store :loadouts) [:loadouts id :loadout/extras]))))
      (s/ship-table! driver)
      (s/check! driver (str "[data-export-id='" id "']"))
      (s/wait-visible! driver "#export-controls")
      (s/check! driver (str "[data-export-id='" second-id "']"))
      (s/wait-visible! driver "#export-controls p:text-is('2 selected')")
      (is (str/includes? (s/text driver "#export-controls") "Second cruiser"))
      (is (str/includes? (s/text driver "#export-controls") "unsupported-pitted"))
      (s/fill-and-blur! driver "#export-controls input[name=directory]" directory)
      (s/fill-and-blur! driver "#export-controls input[name=diameter]" "1.5")
      (s/click! driver "#export-controls button[value='unsupported:up']")
      (s/wait-visible! driver "#export-controls")
      (s/click! driver (str "#export-controls button[name=preview][value='" hull "'] >> nth=0"))
      (s/wait-visible! driver "#export-controls")
      (testing "the viewport shows the actual cut geometry"
        (is (s/wait-until #(some #{hull} (:parts (s/stats driver)))))
        (is (> (:triangles (s/stats driver)) 12)))
      (s/screenshot-el! driver "#viewport" (fs/file "/tmp/shipyard-export-preview.png"))
      (s/click! driver "#export-controls button[name=action][value=export]")
      (s/wait-visible! driver "#export-controls a[download]")
      (let [files (fs/glob directory "*.zip")]
        (is (= 1 (count files)))
        (with-open [zip (ZipFile. (fs/file (first files)))]
          (is (.getEntry zip "Print cruiser/1x-hull-unsupported.stl"))
          (is (.getEntry zip "Print cruiser/3x-weapon-unsupported.stl"))
          (is (.getEntry zip "Second cruiser/1x-hull-unsupported.stl"))
          (is (.getEntry zip "Second cruiser/2x-weapon-unsupported.stl"))
          (is (.getEntry zip "Print cruiser/index.csv"))
          (is (.getEntry zip "Second cruiser/index.csv"))
          (is (every? #(= 2 (count (str/split (.getName %) #"/"))) (enumeration-seq (.entries zip))))))
      (testing "export options and selection survive workspace round trips"
        (s/click! driver "[data-workspace-mode=settings]")
        (s/wait-visible! driver "#settings")
        (s/ship-table! driver)
        (is (s/js driver (str "() => document.querySelector('[data-export-id=\"" id "\"]').checked")))
        (s/click! driver "button:text-is('Export selected ships')")
        (s/wait-visible! driver "#export-controls")
        (is (= "1.5" (s/js driver "() => document.querySelector('#export-controls input[name=diameter]').value"))))
      (testing "diameter validation is recoverable and preserves completed output"
        (s/fill-and-blur! driver "#export-controls input[name=diameter]" "-1")
        (s/js driver "() => document.querySelector('#export-controls form').noValidate=true")
        (s/click! driver "#export-controls button[name=action][value=export]")
        (s/wait-visible! driver "#export-controls [role=alert]")
        (is (= 1 (count (fs/glob directory "*.zip"))))
        (is (str/includes? (s/text driver "#export-controls [role=alert]") "diameter")))
      (testing "selection can be cleared after correcting an error"
        (s/click! driver "#export-controls button:text-is('Clear selection')")
        (s/wait-visible! driver "#export-controls p:text-is('0 selected')")
        (is (s/wait-until #(zero? (s/count-els driver "[data-export-id]:checked")))))
      (finally (s/quit! driver) (fixture/stop! started)))))
