(ns shipyard.e2e.fleet-export-workflow-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.fleet.db :as fleets]
            [shipyard.loadout.db :as classes]
            [shipyard.loadout-fixture :as lf])
  (:import [com.microsoft.playwright Page]
           [java.util.zip ZipFile]))

(defn- variants-library! [root]
  (fixture/library! root)
  (fs/copy (fs/path root (:hull fixture/ids) "unsupported.stl")
           (fs/path root (:hull fixture/ids) "supported.stl"))
  root)

(deftest settings-to-fleet-bundle-and-failed-export
  (s/assert-bundle!)
  (let [started (fixture/start! true variants-library!) sys (:system started) driver (s/make-driver) ^Page page (:page driver)
        facade {:store (:shipyard.store/db sys) :catalog (:shipyard.catalog/db sys)}
        class {:loadout/id (random-uuid) :loadout/name "Print cruiser"
               :loadout/hull (:hull fixture/ids) :loadout/slots lf/assignments}
        directory (str (fs/path (:temp started) "exports"))]
    (try
      (classes/put! (:shipyard.loadout/db sys) class :create)
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver "#bulk-orient-results .bulk-orient__row")
      (s/click! driver "[data-workspace-mode=settings]")
      (s/wait-visible! driver "#export-settings")
      (s/fill-and-blur! driver "#export-settings input[name=export-root]" directory)
      (s/click! driver "#export-settings button:text-is('Save export directory')")
      (s/wait-visible! driver "#settings-workspace [role=status]")
      (s/click! driver "[data-default-variants] [data-variant=supported] button:text-is('Up')")
      (is (s/wait-until #(= "supported" (s/js driver "() => document.querySelector('[data-default-variants] li').dataset.variant"))))
      (let [record (:fleet (fleets/create! facade "Print fleet"))]
        (fleets/put! facade (assoc record :fleet/entries {(:loadout/id class) 2}
                                   :fleet/extras {(:weapon fixture/ids) 3}) :update)
        (s/click! driver "[data-workspace-mode=fleets]")
        (s/wait-visible! driver "#fleet-results tbody tr")
        (.dblclick page "[aria-label='Open fleet Print fleet']")
        (s/wait-visible! driver "#fleet-export-controls")
        (let [row (str "[data-fleet-file='" (:weapon fixture/ids) "']")]
          (is (= "11" (s/text driver (str row " [data-fleet-quantity]")))))
        (is (str/includes? (s/text driver (str "[data-fleet-file='" (:hull fixture/ids) "']")) "supported.stl"))
        (s/click! driver "#fleet-export-controls button:text-is('Export ZIP')")
        (s/wait-visible! driver "[data-fleet-export-path]")
        (let [path (s/js driver "() => document.querySelector('[data-fleet-export-path]').dataset.fleetExportPath")]
          (is (fs/regular-file? path))
          (with-open [zip (ZipFile. (fs/file path))]
            (let [manifest (slurp (.getInputStream zip (.getEntry zip "index.csv")))
                  hull (str (:hull fixture/ids) "/supported.stl")
                  weapon (str (:weapon fixture/ids) "/unsupported.stl")]
              (is (str/includes? manifest (str hull ",supported,2")))
              (is (str/includes? manifest (str weapon ",unsupported,11")))
              (is (= (seq (java.nio.file.Files/readAllBytes (fs/path (:root started) hull)))
                     (seq (.readAllBytes (.getInputStream zip (.getEntry zip hull))))))))
          (s/click! driver "#fleet-export-controls button:text-is('Export ZIP')")
          (is (s/wait-until #(= 2 (count (fs/glob directory "*.zip")))))
          (fs/delete (fs/path (:root started) (:weapon fixture/ids) "unsupported.stl"))
          (s/click! driver "#fleet-export-controls button:text-is('Export ZIP')")
          (s/wait-visible! driver "#fleet-editor [role=alert]")
          (is (= 2 (count (fs/glob directory "*.zip"))))
          (is (empty? (fs/glob directory ".shipyard-fleet-*")))))
      (finally (s/quit! driver) (fixture/stop! started)))))
