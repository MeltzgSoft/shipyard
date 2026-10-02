(ns shipyard.e2e.payload-risks-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.loadout.db :as classes]
            [shipyard.ship.db :as ships]
            [shipyard.loadout-fixture :as lf]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.part-regions-test :as regions]
            [shipyard.e2e.detail-brush-test :as brush])
  (:import [com.microsoft.playwright Page]))

(deftest parts-load-incrementally-and-retain-selection-and-return-position
  (let [started (fixture/start! true (fn [root]
                                       (fixture/library! root)
                                       (doseq [n (range 60)]
                                         (fs/copy-tree (fs/path root (:weapon fixture/ids))
                                                       (fs/path root (format "Synthetic Navy/Cruiser/Part %02d" n))))
                                       root))
        driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver ".bulk-orient__row")
      (is (= 50 (s/count-els driver ".bulk-orient__row")))
      (s/click! driver "[data-select-all=all]")
      (is (s/wait-until #(= "69 selected" (s/text driver "[data-bulk-count]")))
          "Select all includes unloaded matches and excludes the supported-only part")
      (s/click! driver "[data-select-all=none]")
      (is (s/wait-until #(= "0 selected" (s/text driver "[data-bulk-count]"))))
      (s/check! driver "[data-list-page=\"1\"] .bulk-orient__row:nth-child(3) input[type=checkbox]")
      (s/wait-visible! driver "[data-bulk-count]:text-is('1 selected')")
      (s/scroll-into-view! driver "#bulk-orient-results .list-more")
      (is (s/wait-until #(= "2" (s/js driver "() => document.querySelector('[data-part-page]').value"))))
      (s/check! driver "[data-list-page=\"2\"] .bulk-orient__row:nth-child(3) input[type=checkbox]")
      (s/wait-visible! driver "[data-bulk-count]:text-is('2 selected')")
      (.dblclick ^Page (:page driver) "[data-list-page=\"2\"] .bulk-orient__row:nth-child(3) .bulk-orient__part")
      (s/wait-visible! driver "[data-part-back]")
      (s/click! driver "[data-part-back]")
      (s/wait-visible! driver ".bulk-orient__row")
      (is (= "2" (s/js driver "() => document.querySelector('[data-part-page]').value")))
      (is (= 2 (s/count-els driver ".bulk-orient__row input:checked")))
      (s/fill! driver "#bulk-orient-filters input[name=q]" "Part 00")
      (is (s/wait-until #(= 1 (s/count-els driver ".bulk-orient__row"))))
      (is (= "1" (s/js driver "() => document.querySelector('[data-part-page]').value")))
      (is (= "2 selected" (s/text driver "[data-bulk-count]")))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest classes-and-expanded-hulls-load-independent-batches
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        class-db (:shipyard.loadout/db sys) ship-db (:shipyard.ship/db sys)
        ids (vec (repeatedly 60 random-uuid))]
    (try
      (doseq [[n id] (map-indexed vector ids)]
        (classes/put! class-db {:loadout/id id :loadout/name (format "Class %02d" n)
                                :loadout/hull (:hull lf/draft) :loadout/slots {}} :create))
      (doseq [n (range 60)]
        (ships/put! ship-db {:ship/id (random-uuid) :ship/name (format "Vessel %02d" n)
                             :ship/class (first ids) :ship/paint {}} :create))
      (s/go! driver (s/base-url sys))
      (s/ship-table! driver)
      (is (= 50 (s/count-els driver ".ship-card")))
      (is (zero? (s/count-els driver ".ship-table__named")))
      (s/click! driver ".ship-card:first-of-type summary")
      (s/wait-visible! driver ".ship-table__named")
      (is (= 50 (s/count-els driver ".ship-table__named")))
      (s/scroll-into-view! driver ".ship-card__ships .list-more")
      (s/wait-visible! driver "[aria-label='Open ship Vessel 59']")
      (is (= 60 (s/count-els driver ".ship-table__named")))
      (s/scroll-into-view! driver "#ship-results > .list-more")
      (s/wait-visible! driver "[aria-label='Open class Class 59']")
      (is (= 60 (s/count-els driver ".ship-card")))
      (.dblclick ^Page (:page driver) "[aria-label='Open class Class 59']")
      (s/wait-visible! driver "[data-ship-back]")
      (s/click! driver "[data-ship-back]")
      (s/wait-visible! driver "[aria-label='Open class Class 59']")
      (is (= "2" (s/js driver "() => document.querySelector('[data-ship-page]').value")))
      (is (= 60 (s/count-els driver ".ship-table__named")))
      (s/fill! driver "#ship-filters input[name=q]" "Vessel 59")
      (is (s/wait-until #(= 1 (s/count-els driver ".ship-card"))))
      (s/wait-visible! driver "[aria-label='Open ship Vessel 59']")
      (is (= 1 (s/count-els driver ".ship-card")))
      (is (= 1 (s/count-els driver ".ship-table__named")))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest region-patches-recover-a-lost-baseline
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids)
        saved #(catalog/part-regions (:part (catalog/part-context! cat id)))]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "weapon")
      (s/await-part driver id)
      (s/click! driver "[data-detail-tab=regions]")
      (apply brush/stroke! driver (regions/region-point driver 0))
      (is (s/wait-until #(seq (:faces (saved)))))
      (is (s/wait-until #(s/js driver "() => !!document.querySelector('#part-regions').dataset.regionDelta")))
      ;; Simulate losing the preserved baseline while the server's revision advances.
      (s/js driver "() => {window.regionErrors=[]; window.addEventListener('error',e=>window.regionErrors.push(e.message)); document.querySelector('#region-snapshot').shipyardRegions=null; window.recoveries=0; document.body.addEventListener('htmx:beforeRequest',e=>{if(e.detail.requestConfig.path.startsWith('/parts/regions/snapshot')) window.recoveries++}); }")
      (s/fill-and-blur! driver "#region-add input[name=name]" "Recovered")
      (s/click! driver "button:text-is('Add layer')")
      (is (s/wait-until #(= 1 (s/js driver "() => window.recoveries")))
          (s/js driver "() => ({errors:window.regionErrors,pending:document.querySelector('#part-regions').shipyardRecoveryPending,meta:document.querySelector('#part-regions').dataset.regions,status:document.querySelector('#region-status').textContent,cached:!!document.querySelector('#region-snapshot').shipyardRegions})"))
      (is (s/wait-until #(s/js driver "() => !!document.querySelector('#part-regions').dataset.regionFaces")))
      (is (= (set (keys (:faces (saved))))
             (set (s/js driver "() => Object.keys(JSON.parse(document.querySelector('#part-regions').dataset.regionFaces))"))))
      (is (= "loaded" (:status (s/stats driver))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest large-mount-events-reach-the-viewport-once
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids)]
    (try
      (catalog/save-mounts! cat id
                            (mapv #(assoc fixture/plug :mount/id (keyword (str "mount-" %))) (range 100)))
      (s/go! driver (s/base-url sys))
      (s/js driver "() => {window.meshEvents=0; document.body.addEventListener('shipyard:load-mesh',()=>window.meshEvents++);}")
      (s/open-part! driver "weapon")
      (s/await-part driver id)
      (is (= 100 (+ (get-in (s/stats driver) [:interfaces :count])
                    (get-in (s/stats driver) [:interfaces :misses]))))
      (is (= 1 (s/js driver "() => window.meshEvents")))
      (is (zero? (s/count-els driver "[data-viewport-events]")))
      (s/click! driver "[data-detail-tab=regions]")
      (s/fill-and-blur! driver "#region-add input[name=name]" "Event check")
      (s/click! driver "button:text-is('Add layer')")
      (s/wait-visible! driver "button[aria-label='Rename Event check']")
      (is (= 1 (s/js driver "() => window.meshEvents")))
      (finally (s/quit! driver) (fixture/stop! started)))))
