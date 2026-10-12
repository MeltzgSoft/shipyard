(ns shipyard.e2e.fleet-editor-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [integrant.core :as ig]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.fleet.db :as fleet]
            [shipyard.loadout.db :as loadout]
            [shipyard.store.db :as store]
            [shipyard.system :as system])
  (:import [com.microsoft.playwright Page Dialog]
           [java.util.function Consumer]))

(defn- library! [root]
  (fixture/library! root)
  (fs/copy (fs/path root (:weapon fixture/ids) "unsupported.stl")
           (fs/path root (:weapon fixture/ids) "supported.stl"))
  root)

(defn- class! [sys]
  (let [record {:loadout/id (random-uuid) :loadout/name "Print cruiser" :loadout/hull (:hull fixture/ids)
                :loadout/slots {[[:weapon 0]] (:weapon fixture/ids) [[:weapon 1]] (:weapon fixture/ids)}}]
    (loadout/put! (:shipyard.loadout/db sys) record :create)
    record))

(defn- create! [driver name]
  (s/fill-and-blur! driver ".fleet-table header input[name=name]" name)
  (s/click! driver ".fleet-table header button:text-is('New fleet')")
  (s/wait-visible! driver (str "#fleet-editor h2:text-is('" name "')"))
  (parse-uuid (s/js driver "() => document.querySelector('#fleet-editor').dataset.fleetId")))

(defn- back! [driver]
  (s/click! driver "#fleet-editor button:text-is('← Back to fleets')")
  (s/wait-visible! driver "#fleet-filters"))

(defn- open! [driver name]
  (.dblclick ^Page (:page driver) (str "[aria-label='Open fleet " name "']"))
  (s/wait-visible! driver (str "#fleet-editor h2:text-is('" name "')")))

(deftest edit-fleet-print-quantities-preview-and-persist-after-restart
  (s/assert-bundle!)
  (let [started (fixture/start! true library! fixture/author!) sys (:system started) running (atom sys)
        driver (s/make-driver) ^Page page (:page driver) db (:shipyard.fleet/db sys)
        class (class! sys) class-id (:loadout/id class)
        class-row (str "[data-fleet-class='" class-id "']")
        extra-row (str "[data-fleet-extra='" (:weapon fixture/ids) "']")
        file-row (str "[data-fleet-file='" (:weapon fixture/ids) "']")
        ship-assembly @(:state (:shipyard.assembly/db sys))
        ship-preview @(:state (:shipyard.loadout.operations/preview sys))]
    (try
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver "#bulk-orient-filters")
      (s/open-part! driver "prow")
      (s/await-part driver (:prow fixture/ids))
      (let [pose (:quaternion (s/stats driver))]
        (s/click! driver "[data-workspace-mode=fleets]")
        (s/wait-visible! driver "#fleet-filters")
        (let [a (create! driver "Printing fleet")]
          (s/select-option! driver "#fleet-add-class select" "Print cruiser")
          (s/fill-and-blur! driver "#fleet-add-class input[name=quantity]" "3")
          (s/click! driver "#fleet-add-class button")
          (s/wait-visible! driver class-row)
          (is (= 3 (get-in (fleet/record! db a) [:fleet/entries class-id])))
          (s/select-option! driver "#fleet-add-extra select" (:weapon fixture/ids))
          (s/fill-and-blur! driver "#fleet-add-extra input[name=quantity]" "4")
          (s/click! driver "#fleet-add-extra button")
          (s/wait-visible! driver extra-row)
          (is (= "10" (s/text driver (str file-row " [data-fleet-quantity]"))))
          (s/fill-and-blur! driver (str class-row " input[name=quantity]") "2")
          (s/click! driver (str class-row " button:text-is('Save quantity')"))
          (is (s/wait-until #(= "8" (s/text driver (str file-row " [data-fleet-quantity]")))))
          (s/fill-and-blur! driver (str class-row " input[name=quantity]") "3")
          (s/click! driver (str class-row " button:text-is('Save quantity')"))
          (is (s/wait-until #(= "10" (s/text driver (str file-row " [data-fleet-quantity]")))))
          (s/fill-and-blur! driver (str extra-row " input[name=quantity]") "5")
          (s/click! driver (str extra-row " button:text-is('Save quantity')"))
          (is (s/wait-until #(= "11" (s/text driver (str file-row " [data-fleet-quantity]")))))
          (s/fill-and-blur! driver (str extra-row " input[name=quantity]") "4")
          (s/click! driver (str extra-row " button:text-is('Save quantity')"))
          (is (s/wait-until #(= "10" (s/text driver (str file-row " [data-fleet-quantity]")))))
          (testing "server quantity errors retain the list and valid quantities"
            (s/js driver "() => {const f=document.querySelector('[data-fleet-class] form');f.noValidate=true;f.querySelector('[name=quantity]').value='0'}")
            (s/click! driver (str class-row " button:text-is('Save quantity')"))
            (s/wait-visible! driver "#fleet-editor [role=alert]")
            (is (= 3 (get-in (fleet/record! db a) [:fleet/entries class-id])))
            (is (= (str a) (s/js driver "() => document.querySelector('#fleet-editor').dataset.fleetId"))))
          (s/click! driver (str class-row " button:text-is('Preview class')"))
          (s/await-assembly-prepared! driver 3)
          (is (= ship-assembly @(:state (:shipyard.assembly/db sys))))
          (is (= ship-preview @(:state (:shipyard.loadout.operations/preview sys))))
          (is (= class (loadout/record! (:shipyard.loadout/db sys) class-id)))
          (is (= class-id (get-in @(:state (:shipyard.fleet.handlers/preview sys)) [:draft :loadout-id])))
          (s/click! driver "[data-workspace-mode=browse]")
          (s/await-part driver (:prow fixture/ids))
          (is (= pose (:quaternion (s/stats driver))))
          (s/click! driver "[data-workspace-mode=fleets]")
          (s/wait-visible! driver "#fleet-editor")
          (s/await-assembly-prepared! driver 3)
          (s/click! driver "[aria-label='Move fleet supported up']")
          (is (s/wait-until #(= [:supported :unsupported :unsupported-pitted] (:fleet/variant-preference (fleet/record! db a)))))
          (is (s/wait-until #(= "supported" (s/text driver (str file-row " td:nth-child(2)")))))
          (is (= "unsupported" (s/text driver (str "[data-fleet-file='" (:hull fixture/ids) "'] td:nth-child(2)"))))
          (s/click! driver "[aria-label='Move fleet supported down']")
          (is (s/wait-until #(= [:unsupported :supported :unsupported-pitted] (:fleet/variant-preference (fleet/record! db a)))))
          (store/write! (:shipyard.store/db sys) #(d/transact! % [{:store/key "shipyard" :settings/variant-preference [:supported :unsupported-pitted :unsupported]}]))
          (back! driver)
          (let [b (create! driver "Another fleet")]
            (is (= [:supported :unsupported-pitted :unsupported] (:fleet/variant-preference (fleet/record! db b))))
            (is (= [:unsupported :supported :unsupported-pitted] (:fleet/variant-preference (fleet/record! db a))))
            (back! driver)
            (open! driver "Printing fleet")
            (s/click! driver "#fleet-editor button:text-is('Use default')")
            (is (s/wait-until #(= [:supported :unsupported-pitted :unsupported] (:fleet/variant-preference (fleet/record! db a)))))
            (store/write! (:shipyard.store/db sys) #(d/transact! % [{:store/key "shipyard" :settings/variant-preference [:unsupported]}]))
            (is (= [:supported :unsupported-pitted :unsupported] (:fleet/variant-preference (fleet/record! db a))))
            (testing "an exhaustive preference diagnoses missing usable variants"
              (store/write! (:shipyard.store/db sys) #(d/transact! % [{:store/key "shipyard" :settings/variant-preference [:unsupported-pitted]}]))
              (s/click! driver "#fleet-editor button:text-is('Use default')")
              (s/wait-visible! driver "[data-fleet-ready=false]")
              (is (pos? (s/count-els driver "[data-fleet-plan-error]")))
              (is (zero? (s/count-els driver "[data-fleet-file]")))
              (store/write! (:shipyard.store/db sys) #(d/transact! % [{:store/key "shipyard" :settings/variant-preference [:supported :unsupported-pitted :unsupported]}]))
              (s/click! driver "#fleet-editor button:text-is('Use default')")
              (s/wait-visible! driver "[data-fleet-ready=true]"))
            (s/click! driver (str extra-row " button:text-is('Remove extra part')"))
            (is (s/wait-until #(empty? (:fleet/extras (fleet/record! db a)))))
            (is (s/wait-until #(= "6" (s/text driver (str file-row " [data-fleet-quantity]")))))
            (s/click! driver (str class-row " button:text-is('Remove class')"))
            (is (s/wait-until #(empty? (:fleet/entries (fleet/record! db a)))))
            (is (s/wait-until #(zero? (s/count-els driver class-row))))
            (s/select-option! driver "#fleet-add-class select" "Print cruiser")
            (s/fill-and-blur! driver "#fleet-add-class input[name=quantity]" "5")
            (s/click! driver "#fleet-add-class button")
            (s/wait-visible! driver class-row)
            (s/fill-and-blur! driver "#fleet-name input[name=name]" "Renamed printing fleet")
            (s/click! driver "#fleet-name button")
            (s/wait-visible! driver "#fleet-editor h2:text-is('Renamed printing fleet')")
            (testing "restart preserves durable quantities and preferences"
              (ig/halt! @running)
              (reset! running nil)
              (reset! running
                      (system/start!
                       (-> (system/load-config! {:profile :test :config-dir (str (fs/path (:temp started) "config")) :env {}})
                           (assoc-in [:shipyard.store/db :data-home] (str (fs/path (:temp started) "data")))
                           (assoc-in [:shipyard.mesh/cache :cache-home] (str (fs/path (:temp started) "cache")))
                           (assoc-in [:shipyard.http/server :port] 0))))
              (s/go! driver (s/base-url @running))
              (s/click! driver "[data-workspace-mode=fleets]")
              (s/wait-visible! driver "#fleet-filters")
              (open! driver "Renamed printing fleet")
              (is (= "5" (s/js driver "() => document.querySelector('[data-fleet-class] [name=quantity]').value")))
              (is (= "10" (s/text driver (str file-row " [data-fleet-quantity]"))))
              (is (= [:supported :unsupported-pitted :unsupported]
                     (:fleet/variant-preference (fleet/record! (:shipyard.fleet/db @running) a)))))
            (back! driver)
            (open! driver "Another fleet")
            (.onDialog page (reify Consumer (accept [_ dialog] (.accept ^Dialog dialog))))
            (s/click! driver "#fleet-editor button:text-is('Delete fleet')")
            (s/wait-visible! driver "#fleet-filters")
            (is (nil? (fleet/record! (:shipyard.fleet/db @running) b))))))
      (finally (s/quit! driver) (fixture/stop! (assoc started :system @running))))))
