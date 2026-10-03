(ns shipyard.e2e.part-drawer-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.import-fixture :as archives]
            [shipyard.importer.db :as importer])
  (:import [com.microsoft.playwright Page]))

(defn- edit-drawer! [driver drawer]
  (s/click! driver (str drawer " > summary"))
  (s/wait-visible! driver (str drawer " .part-thumbnail--large img"))
  (is (= 256 (s/js driver (str "() => document.querySelector(" (pr-str (str drawer " .part-thumbnail--large img")) ").naturalWidth"))))
  (is (= 256.0 (s/width driver (str drawer " .part-thumbnail--large"))))
  (s/fill-and-blur! driver (str drawer " input[name=name]") "Drawer Part")
  (doseq [[field value] [["bundle" "Drawer Fleet"] ["class" "Drawer Class"] ["role" "Sensor Array"]]]
    (.fill ^Page (:page driver) (str drawer " input[name=" field "]") value)
    (s/click! driver (str drawer " .classification-picker:has(input[name=" field "]) [role=option]:text-is('Add “" value "”')")))
  (s/click! driver (str drawer " button:text-is('Save part')"))
  (s/wait-visible! driver (str drawer " [role=status]:text-is('Saved.')"))
  (is (= "Drawer Part" (s/text driver (str drawer " .bulk-orient__part"))))
  (is (= "Drawer Fleet" (s/text driver (str drawer " .bulk-orient__bundle")))))

(deftest drawer-save-is-independent-and-retains-other-drafts
  (let [started (fixture/start! true) driver (s/make-driver) sys (:system started)
        cat (:shipyard.catalog/db sys) a (:prow fixture/ids) b (:bridge fixture/ids)
        drawer (str "[data-part-row='" a "']") other (str "[data-part-row='" b "']")
        before (catalog/summary! cat b)]
    (try
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver drawer)
      (s/check! driver (str other " [data-bulk-select]"))
      (s/wait-visible! driver "[data-bulk-count]:text-is('1 selected')")
      (is (zero? (s/count-els driver (str other "[open]"))) "Selecting a row does not open its drawer")
      (s/click! driver (str other " > summary"))
      (s/fill-and-blur! driver (str other " input[name=name]") "Unsaved other part")
      (edit-drawer! driver drawer)
      (is (= before (catalog/summary! cat b)))
      (is (= "Unsaved other part" (s/js driver (str "() => document.querySelector(" (pr-str (str other " input[name=name]")) ").value"))))
      (is (= "1 selected" (s/text driver "[data-bulk-count]")))
      (s/fill-and-blur! driver (str drawer " input[name=role]") "bad/role")
      (s/click! driver (str drawer " button:text-is('Save part')"))
      (s/wait-visible! driver (str drawer " [role=status] .detail__error"))
      (is (= :sensor-array (:part/role-hint (catalog/summary! cat a))))
      (s/fill-and-blur! driver (str drawer " input[name=role]") "sensor-array")
      (s/screenshot-el! driver drawer (java.io.File. "/tmp/shipyard-part-drawer.png"))
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver (str drawer " .bulk-orient__part:text-is('Drawer Part')"))
      (is (= "Drawer Class" (:part/class (catalog/summary! cat a))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest import-drawer-saves-to-staging-and-cancel-discards-it
  (let [started (fixture/start! true) driver (s/make-driver) sys (:system started)
        cat (:shipyard.catalog/db sys) before (catalog/listing! cat)
        directory (fs/create-temp-dir) zip (archives/archive! directory)]
    (try
      (s/go! driver (s/base-url sys))
      (s/choose-path! driver ".import-start" zip)
      (s/wait-visible! driver ".import-review")
      (let [id (s/js driver "() => document.querySelector('[data-part-row]').dataset.partRow")
            drawer (str "[data-part-row='" id "']")]
        (edit-drawer! driver drawer)
        (is (= 1 (s/count-els driver (str drawer " .import-files summary"))))
        (is (not= "None" (s/text driver (str drawer " .bulk-orient__mounts"))))
        (is (= :sensor-array (:part/role-hint (catalog/summary! (:catalog (importer/session! {:workspace (:shipyard.workspace/db sys)})) id))))
        (is (= before (catalog/listing! cat)))
        (s/screenshot-el! driver drawer (java.io.File. "/tmp/shipyard-import-drawer.png")))
      (s/click! driver "form[hx-post='/imports/cancel'] button")
      (s/wait-visible! driver ".import-start")
      (is (= before (catalog/listing! cat)))
      (finally (s/quit! driver) (fixture/stop! started) (fs/delete-tree directory)))))
