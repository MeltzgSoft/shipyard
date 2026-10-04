(ns shipyard.e2e.part-drawer-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [shipyard.fixtures :as meshes]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.import-fixture :as archives]
            [shipyard.importer.db :as importer]
            [shipyard.part.orientation :as orientation])
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
  (doseq [[field value] [["part-yaw-deg" "90"] ["part-pitch-deg" "-15"] ["part-roll-deg" "5.5"]]]
    (.fill ^Page (:page driver) (str drawer " input[name=" field "]") value))
  (s/click! driver (str drawer " button:text-is('Save part')"))
  (s/wait-visible! driver (str drawer " [role=status]:text-is('Saved.')"))
  (s/wait-visible! driver (str drawer " .part-thumbnail--large img"))
  (is (= 256 (s/js driver (str "() => document.querySelector(" (pr-str (str drawer " .part-thumbnail--large img")) ").naturalWidth"))))
  (is (= "Drawer Part" (s/text driver (str drawer " .bulk-orient__part"))))
  (is (= "Drawer Fleet" (s/text driver (str drawer " .bulk-orient__bundle")))))

(def drawer-pose (orientation/from-euler-degrees 90 -15 5.5))

(defn- pose-close? [a b]
  (and (= 4 (count a) (count b)) (every? #(< (Math/abs (double %)) 1e-6) (map - a b))))

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
      (.fill ^Page (:page driver) (str other " input[name=part-yaw-deg]") "45")
      (edit-drawer! driver drawer)
      (is (= before (catalog/summary! cat b)))
      (is (= drawer-pose (:part/orientation (catalog/summary! cat a))))
      (is (= "45" (s/js driver (str "() => document.querySelector(" (pr-str (str other " input[name=part-yaw-deg]")) ").value"))))
      (is (= "Unsaved other part" (s/js driver (str "() => document.querySelector(" (pr-str (str other " input[name=name]")) ").value"))))
      (is (= "1 selected" (s/text driver "[data-bulk-count]")))
      (s/fill-and-blur! driver (str drawer " input[name=role]") "bad/role")
      (s/click! driver (str drawer " button:text-is('Save part')"))
      (s/wait-visible! driver (str drawer " [role=status] .detail__error"))
      (is (= :sensor-array (:part/role-hint (catalog/summary! cat a))))
      (s/fill-and-blur! driver (str drawer " input[name=role]") "sensor-array")
      (.fill ^Page (:page driver) (str drawer " input[name=part-yaw-deg]") "36001")
      (s/fill-and-blur! driver (str drawer " input[name=name]") "Rejected name")
      (s/click! driver (str drawer " button:text-is('Save part')"))
      (s/wait-visible! driver (str drawer " [role=status] .detail__error"))
      (is (= "Drawer Part" (:part/name (catalog/summary! cat a))))
      (is (= drawer-pose (:part/orientation (catalog/summary! cat a))))
      (.fill ^Page (:page driver) (str drawer " input[name=part-yaw-deg]") "90")
      (s/fill-and-blur! driver (str drawer " input[name=name]") "Drawer Part")
      (s/click! driver (str drawer " button:text-is('Save part')"))
      (s/wait-visible! driver (str drawer " [role=status]:text-is('Saved.')"))
      (s/wait-visible! driver (str drawer " .part-thumbnail--large img"))
      (s/screenshot-el! driver drawer (java.io.File. "/tmp/shipyard-part-drawer.png"))
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver (str drawer " .bulk-orient__part:text-is('Drawer Part')"))
      (is (= "Drawer Class" (:part/class (catalog/summary! cat a))))
      (s/open-part! driver "Drawer Part")
      (s/await-part driver a)
      (is (s/wait-until #(pose-close? drawer-pose (:orientation (s/stats driver)))))
      (s/click! driver "[data-part-back]")
      (s/wait-visible! driver drawer)
      (s/click! driver (str drawer " > summary"))
      (s/wait-visible! driver (str drawer " [data-row-orientation-reset]"))
      (s/click! driver (str drawer " [data-row-orientation-reset]"))
      (is (= drawer-pose (:part/orientation (catalog/summary! cat a))) "Reset waits for Save part")
      (s/click! driver (str drawer " button:text-is('Save part')"))
      (s/wait-visible! driver (str drawer " [role=status]:text-is('Saved.')"))
      (is (= orientation/identity-quaternion (:part/orientation (catalog/summary! cat a))))
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
        (is (= drawer-pose (:part/orientation (catalog/summary! (:catalog (importer/session! {:workspace (:shipyard.workspace/db sys)})) id))))
        (is (= before (catalog/listing! cat)))
        (s/screenshot-el! driver drawer (java.io.File. "/tmp/shipyard-import-drawer.png")))
      (s/click! driver "form[hx-post='/imports/cancel'] button")
      (s/wait-visible! driver ".import-start")
      (is (= before (catalog/listing! cat)))
      (finally (s/quit! driver) (fixture/stop! started) (fs/delete-tree directory)))))

(deftest supported-only-import-rows-retain-metadata-editing-with-disabled-orientation
  (let [started (fixture/start! true) driver (s/make-driver) sys (:system started)
        zip (fs/file (:temp started) "Supported Fleet.zip")]
    (try
      (with-open [out (io/output-stream zip)]
        (.write out ^bytes (archives/zip-bytes [["Cruiser/Supported Files/Bridge.stl" (meshes/->binary-stl (meshes/cube))]])))
      (s/go! driver (s/base-url sys))
      (s/choose-path! driver ".import-start" zip)
      (s/wait-visible! driver ".import-review")
      (s/click! driver ".part-drawer > summary")
      (s/wait-visible! driver ".part-row-edit__orientation")
      (is (s/js driver "() => document.querySelector('.part-row-edit__orientation').disabled"))
      (is (not (s/js driver "() => document.querySelector('.part-row-edit input[name=name]').disabled")))
      (s/fill-and-blur! driver ".part-row-edit input[name=name]" "Supported Bridge")
      (s/click! driver ".part-row-edit button:text-is('Save part')")
      (s/wait-visible! driver ".part-row-edit [role=status]:text-is('Saved.')")
      (is (s/js driver "() => document.querySelector('.part-row-edit__orientation').disabled"))
      (is (not (s/js driver "() => document.querySelector('.part-row-edit input[name=name]').disabled")))
      (is (= "Supported Bridge" (s/text driver ".bulk-orient__part")))
      (finally (s/quit! driver) (fixture/stop! started)))))
