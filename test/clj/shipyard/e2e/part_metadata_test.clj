(ns shipyard.e2e.part-metadata-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.part.orientation :as orientation])
  (:import [com.microsoft.playwright Page]))

(deftest individual-part-edits-all-shared-fields-and-retains-the-model
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        ^Page page (:page driver) cat (:shipyard.catalog/db sys) id (:weapon fixture/ids)
        pose (orientation/from-euler-degrees 30 15 -5)]
    (try
      (catalog/save-part-orientation! cat id pose)
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "weapon")
      (s/await-part driver id)
      (let [viewport (select-keys (s/stats driver) [:parts :orientation])
            stored-pose (:part/orientation (catalog/summary! cat id))]
        (s/js driver "() => {window.metadataCanvas=document.getElementById('viewport');window.metadataRegions=document.getElementById('part-regions')}")
        (.fill page ".part-metadata__form input[name=name]" "Named Battery")
        (doseq [[field value] [["bundle" "Shared Navy"] ["class" "Carrier"] ["role" "Sensor Array"]]]
          (.fill page (str ".part-metadata__form input[name=" field "]") value)
          (s/click! driver (str ".part-metadata__form .classification-picker:has(input[name=" field "]) [role=option]:text-is('Add “" value "”')")))
        (s/click! driver ".part-metadata__form button:text-is('Save metadata')")
        (s/wait-visible! driver ".part-metadata__form [role=status]:text-is('Saved.')")
        (is (= "Named Battery" (s/text driver ".detail__name")))
        (is (= stored-pose (:part/orientation (catalog/summary! cat id))))
        (is (= viewport (select-keys (s/stats driver) [:parts :orientation])))
        (is (s/js driver "() => window.metadataCanvas===document.getElementById('viewport') && window.metadataRegions===document.getElementById('part-regions')"))
        (is (= "sensor-array" (s/js driver "() => document.querySelector('.part-metadata__form input[name=role]').value")))
        (.fill page ".part-metadata__form input[name=role]" "bad/role")
        (.fill page ".part-metadata__form input[name=name]" "Rejected Name")
        (s/click! driver ".part-metadata__form button:text-is('Save metadata')")
        (s/wait-visible! driver ".part-metadata__form [role=status] .detail__error")
        (is (= "Named Battery" (:part/name (catalog/summary! cat id))))
        (is (= "Rejected Name" (s/js driver "() => document.querySelector('.part-metadata__form input[name=name]').value")))
        (is (= viewport (select-keys (s/stats driver) [:parts :orientation])))
        (.fill page ".part-metadata__form input[name=role]" "sensor-array")
        (.fill page ".part-metadata__form input[name=name]" "Named Battery")
        (s/click! driver ".part-metadata__form button:text-is('Save metadata')")
        (s/wait-visible! driver ".part-metadata__form [role=status]:text-is('Saved.')")
        (s/screenshot-el! driver ".part-metadata" (java.io.File. "/tmp/shipyard-part-metadata.png"))
        (s/click! driver "[data-part-back]")
        (s/wait-visible! driver (str "[data-part-row='" id "'] .bulk-orient__part:text-is('Named Battery')"))
        (s/open-part! driver "Named Battery")
        (s/await-part driver id)
        (is (= "Shared Navy" (s/js driver "() => document.querySelector('.part-metadata__form input[name=bundle]').value")))
        (is (= "Carrier" (s/js driver "() => document.querySelector('.part-metadata__form input[name=class]').value"))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest individual-and-drawer-fields-accept-the-same-fractional-degrees
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) id (:prow fixture/ids)
        expected (orientation/from-euler-degrees 12.5 -3.25 5.5)
        close? (fn [pose] (and (= 4 (count pose)) (every? #(< (abs (double %)) 1e-7) (map - expected pose))))]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "prow")
      (s/await-part driver id)
      (doseq [[field value] [["part-yaw-deg" "12.5"] ["part-pitch-deg" "-3.25"] ["part-roll-deg" "5.5"]]]
        (.fill ^Page (:page driver) (str ".part-orientation__form input[name=" field "]") value))
      (is (s/js driver "() => document.querySelector('.part-orientation__form').checkValidity()"))
      (is (s/wait-until #(close? (:orientation (s/stats driver)))))
      (s/click! driver ".part-orientation__actions button[value=save]")
      (is (s/wait-until #(close? (:part/orientation (catalog/summary! cat id)))))
      (s/click! driver "[data-part-back]")
      (s/wait-visible! driver (str "[data-part-row='" id "']"))
      (s/click! driver (str "[data-part-row='" id "'] > summary"))
      (s/wait-visible! driver (str "[data-part-row='" id "'] .part-row-edit"))
      (is (= ["12.5" "-3.25" "5.5"]
             (s/js driver (str "() => [...document.querySelectorAll(" (pr-str (str "[data-part-row='" id "'] [data-row-angle]")) ")].map(i=>i.value)"))))
      (is (s/js driver "() => document.querySelector('.part-row-edit').checkValidity()"))
      (s/click! driver ".part-row-edit button:text-is('Save part')")
      (s/wait-visible! driver ".part-row-edit [role=status]:text-is('Saved.')")
      (is (close? (:part/orientation (catalog/summary! cat id))))
      (finally (s/quit! driver) (fixture/stop! started)))))
