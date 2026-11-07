(ns shipyard.e2e.inventory-picker-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.loadout.db :as loadouts]
            [shipyard.persistence-fixture :as persisted])
  (:import [com.microsoft.playwright Page]))

(defn- spare [n] (format "Synthetic Navy/Cruiser/Spare %02d" n))
(def foreign "Other Navy/Escort/Foreign spare")

(defn- library! [root]
  (fixture/library! root)
  (doseq [id (cons foreign (map spare (range 60)))]
    (fs/create-dirs (fs/path root id))
    (fs/copy (fs/path root (:weapon fixture/ids) "unsupported.stl") (fs/path root id "unsupported.stl")))
  root)

(defn- quantity-selector [id] (str "[data-inventory-part='" id "'] input"))
(defn- quantity [driver id]
  (s/js driver (str "() => document.querySelector(" (pr-str (quantity-selector id)) ").value")))

(defn- open! [driver]
  (s/click! driver "[data-inventory-open]")
  (s/wait-visible! driver "dialog.inventory-picker"))

(deftest quantity-edits-survive-pages-and-filters-and-save-with-the-class
  (s/assert-bundle!)
  (let [started (fixture/start! true library!) sys (:system started) driver (s/make-driver)
        store (:shipyard.loadout/db sys) id (random-uuid) gun (:weapon fixture/ids)]
    (try
      (loadouts/put! store {:loadout/id id :loadout/name "Inventory cruiser" :loadout/hull (:hull fixture/ids)
                            :loadout/slots {} :loadout/extras {gun 2}} :create)
      (s/go! driver (s/base-url sys))
      (s/open-class! driver "Inventory cruiser")
      (s/wait-visible! driver "[data-inventory-open]")
      (open! driver)
      (s/fill-and-blur! driver "#extras-picker input[name=q]" "weapon")
      (s/wait-visible! driver (quantity-selector gun))
      (is (= "2" (quantity driver gun)))
      (is (zero? (s/count-els driver (quantity-selector foreign))))
      (is (s/js driver "() => document.querySelector('dialog.inventory-picker').matches(':modal')"))
      (s/fill-and-blur! driver "#extras-picker input[name=q]" "Spare")
      (s/js driver "() => document.querySelector('#extras-picker input[name=q]').focus()")
      (.press (.keyboard ^Page (:page driver)) "Enter")
      (s/wait-visible! driver "#extras-picker-results .results__count:text-is('60 matches')")
      (is (s/js driver "() => document.querySelector('dialog.inventory-picker').open"))
      (s/fill-and-blur! driver (quantity-selector (spare 0)) "7")
      (s/screenshot-el! driver "dialog.inventory-picker" (fs/file "/tmp/shipyard-inventory-modal.png"))
      (s/click! driver "#extras-picker button:text-is('Next')")
      (s/wait-visible! driver (quantity-selector (spare 59)))
      (s/fill-and-blur! driver (quantity-selector (spare 59)) "4")
      (s/click! driver "#extras-picker button:text-is('Previous')")
      (s/wait-visible! driver (quantity-selector (spare 0)))
      (is (= "7" (quantity driver (spare 0))))
      (is (zero? (s/count-els driver "#extras-picker details, #extras-picker [data-workspace-transition]")))
      (s/select-option! driver "#extras-picker select[name=bundle]" "All bundles")
      (s/select-option! driver "#extras-picker select[name=class]" "All classes")
      (s/fill-and-blur! driver "#extras-picker input[name=q]" "Foreign")
      (s/wait-visible! driver (quantity-selector foreign))
      (s/fill-and-blur! driver (quantity-selector foreign) "3")
      (s/click! driver "#extras-picker button:text-is('Add')")
      (is (s/wait-until #(zero? (s/count-els driver "dialog.inventory-picker"))))
      (is (= 1 (count (get-in (s/stats driver) [:assembly :slots]))))
      (s/click! driver ".assembly__save button")
      (s/wait-visible! driver "[role=status]:text-is('Class saved.')")
      (let [expected {gun 2 (spare 0) 7 (spare 59) 4 foreign 3}]
        (is (= expected (get-in (persisted/records! store :loadouts) [:loadouts id :loadout/extras]))))
      (s/go! driver (s/base-url sys))
      (s/open-class! driver "Inventory cruiser")
      (open! driver)
      (s/fill-and-blur! driver "#extras-picker input[name=q]" "Spare 59")
      (s/wait-visible! driver (quantity-selector (spare 59)))
      (is (= "4" (quantity driver (spare 59))))
      (s/fill-and-blur! driver (quantity-selector (spare 59)) "0")
      (.press (.keyboard ^Page (:page driver)) "Escape")
      (is (s/wait-until #(zero? (s/count-els driver "dialog.inventory-picker"))))
      (is (s/js driver "() => document.activeElement.matches('[data-inventory-open]')"))
      (open! driver)
      (s/fill-and-blur! driver "#extras-picker input[name=q]" "Spare 59")
      (s/wait-visible! driver (quantity-selector (spare 59)))
      (is (= "4" (quantity driver (spare 59))))
      (s/click! driver "#extras-picker button:text-is('Cancel')")
      (finally (s/quit! driver) (fixture/stop! started)))))
