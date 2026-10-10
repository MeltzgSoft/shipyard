(ns shipyard.e2e.part-browser-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.orient-table-test :as table]
            [shipyard.e2e.orient-save-test :as orient])
  (:import [com.microsoft.playwright Page]))

(deftest table-edit-detail-back-and-grid
  (s/assert-bundle!)
  (let [started (fixture/start! true) driver (s/make-driver)
        cat (:shipyard.catalog/db (:system started))
        lib (:shipyard.library/index (:system started))
        a (:prow fixture/ids) b (:bridge fixture/ids)
        part #(catalog/part (catalog/snapshot! cat) %)]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver (table/row a))
      (is (= ["Part Browser" "Ship Browser" "Settings"]
             (s/js driver "() => [...document.querySelectorAll('.masthead__mode')].map(e=>e.textContent)")))
      (s/scroll-into-view! driver (table/row a))
      (s/wait-visible! driver (str (table/row a) " .part-thumbnail img"))
      (is (s/js driver "() => document.querySelector('.part-thumbnail img').naturalWidth > 0"))
      (doseq [id [a b]] (s/check! driver (str "[data-bulk-select][value='" id "']")))
      (is (s/wait-until #(= "2 selected" (s/text driver "[data-bulk-count]"))))
      (is (zero? (s/count-els driver ".part-bulk-edit, select[name=field], select[name=operation]")))
      (doseq [[field value] [["bundle" "New Fleet"] ["class" "New Class"] ["role" "prow"] ["name" "Custom part"]]]
        (s/fill-and-blur! driver (str "#part-column-" field) value))
      (s/click! driver "#part-column-update")
      (is (s/wait-until #(every? (fn [id] (= ["Custom part" "New Fleet" "New Class" :prow]
                                             (mapv (part id) [:part/name :part/bundle :part/class :part/role-hint]))) [a b])))
      (catalog/reingest! cat (index/parts! lib) (index/root! lib))
      (is (= "New Fleet" (:part/bundle (part a))))
      (is (= "New Class" (:part/class (part b))))
      (is (= :prow (:part/role-hint (part b))))
      (s/fill! driver "#bulk-orient-filters input[name=q]" "Custom")
      (is (s/wait-until #(= 2 (s/count-els driver ".bulk-orient__row"))))
      (.dblclick ^Page (:page driver) (str (table/row a) " .bulk-orient__part"))
      (s/await-part driver a)
      (s/wait-visible! driver "[data-part-back]")
      (is (s/js driver "() => getComputedStyle(document.getElementById('library')).display === 'none'"))
      (s/click! driver "[data-part-back]")
      (s/wait-visible! driver (table/row a))
      (is (= "2 selected" (s/text driver "[data-bulk-count]")))
      (is (= "Custom" (s/js driver "() => document.querySelector('#bulk-orient-filters input[name=q]').value")))
      (s/click! driver "[data-bulk-render-button]")
      (is (s/wait-until #(= 2 (get-in (s/stats driver) [:bulk :count]))))
      (orient/set-yaw! driver 45)
      (s/open-assembly! driver)
      (s/wait-visible! driver ".assembly__hull")
      (s/click! driver "[data-workspace-mode=browse]")
      (is (s/wait-until #(= 2 (get-in (s/stats driver) [:bulk :dirty]))))
      (orient/save! driver)
      (is (s/wait-until #(zero? (get-in (s/stats driver) [:bulk :dirty]))))
      (s/click! driver "[data-bulk-back]")
      (s/wait-visible! driver (table/row a))
      (is (= "2 selected" (s/text driver "[data-bulk-count]")))
      (is (zero? (s/count-els driver "[data-bulk-angle]")))
      (s/screenshot-el! driver ".layout" (java.io.File. "/tmp/shipyard-part-browser.png"))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest row-navigation
  (let [started (fixture/start! true) driver (s/make-driver) scroll (atom 0)]
    (try
      (s/resize! driver 1280 560)
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver (table/row (:prow fixture/ids)))
      (s/scroll-into-view! driver (table/row (:prow fixture/ids)))
      (s/wait-visible! driver (str (table/row (:prow fixture/ids)) " .part-thumbnail img"))
      (reset! scroll (s/js driver "() => document.getElementById('bulk-orient-results').scrollTop"))
      (is (pos? @scroll))
      (s/open-part! driver "prow")
      (s/await-part driver (:prow fixture/ids))
      (s/click! driver "[data-detail-tab=regions]")
      (s/wait-visible! driver "[data-part-back]")
      (s/click! driver "[data-part-back]")
      (s/wait-visible! driver "[data-bulk-select]")
      (is (s/wait-until #(= @scroll (s/js driver "() => document.getElementById('bulk-orient-results').scrollTop"))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest matching-selection-checkbox
  (let [started (fixture/start! true) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver "#part-select-matching")
      (is (zero? (s/count-els driver "#library .bulk-orient__head")))
      (is (zero? (s/count-els driver "#import-archive, .classification-editor")))
      (is (= 1 (s/count-els driver "#bulk-orient-filters button:text-is('Clear selection')")))
      (s/fill! driver "#bulk-orient-filters input[name=q]" "prow")
      (is (s/wait-until #(= 2 (s/count-els driver ".bulk-orient__row"))))
      (s/check! driver (str "[data-bulk-select][value='" (:prow fixture/ids) "']"))
      (is (s/wait-until #(s/js driver "() => document.querySelector('#part-select-matching').indeterminate")))
      (s/check! driver "#part-select-matching")
      (s/wait-visible! driver "[data-bulk-count]:text-is('2 selected')")
      (is (s/js driver "() => document.querySelector('#part-select-matching').checked && !document.querySelector('#part-select-matching').indeterminate"))
      (s/click! driver (str "[data-bulk-select][value='" (:prow fixture/ids) "']"))
      (is (s/wait-until #(s/js driver "() => document.querySelector('#part-select-matching').indeterminate")))
      (s/fill! driver "#bulk-orient-filters input[name=q]" "no matching part")
      (s/wait-visible! driver ".bulk-orient__empty")
      (is (s/js driver "() => document.querySelector('#part-select-matching').disabled && !document.querySelector('#part-select-matching').checked"))
      (is (= "1 selected" (s/text driver "[data-bulk-count]")))
      (s/click! driver "#bulk-orient-filters button:text-is('Clear selection')")
      (s/wait-visible! driver "[data-bulk-count]:text-is('0 selected')")
      (is (s/js driver "() => document.querySelector('#part-select-matching').disabled"))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest import-action-shares-the-selection-toolbar
  (let [started (fixture/start! true) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver "[data-bulk-select]")
      (is (zero? (s/count-els driver ".library-variants, form form")))
      (is (= 1 (s/count-els driver "#bulk-selection .bulk-orient__selection > .import-start")))
      (is (s/js driver "() => !document.querySelector('.import-start button').disabled"))
      (s/check! driver (str "[data-bulk-select][value='" (:prow fixture/ids) "']"))
      (s/wait-visible! driver "[data-bulk-count]:text-is('1 selected')")
      (doseq [[width height] [[1280 900] [768 900] [1280 360]]]
        (s/resize! driver width height)
        (s/scroll-into-view! driver ".import-start button")
        (let [button (s/bounds driver ".import-start button")
              orient (s/bounds driver "[data-bulk-render-button]")
              results (s/bounds driver "#bulk-orient-results")
              library (s/bounds driver "#library")]
          (is (< (Math/abs (- (:y button) (:y orient))) 1) "Both actions share one row")
          (is (>= (:y button) (+ (:y results) (:height results))) "Import sits below the table")
          (is (>= (:y button) (:y library)))
          (is (<= (+ (:y button) (:height button)) (+ (:y library) (:height library) 1)))))
      (is (s/js driver "() => [...document.querySelectorAll('#bulk-selection > p')].every(p=>p.getBoundingClientRect().height===0)"))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest column-controls-update-hidden-selections-and-ignore-blanks
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) ids [(:prow fixture/ids) (:bridge fixture/ids)]
        initial (catalog/snapshot! cat)]
    (try
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver "[data-bulk-select]")
      (is (s/js driver "() => document.querySelector('#part-column-controls').hidden"))
      (doseq [id ids] (s/check! driver (str "[data-bulk-select][value='" id "']")))
      (s/wait-visible! driver "[data-bulk-count]:text-is('2 selected')")
      (doseq [width [1280 768]]
        (s/resize! driver width 900)
        (doseq [[field column] [["name" 3] ["bundle" 4] ["role" 5] ["class" 6]]]
          (let [control (s/bounds driver (if (= field "name") ".part-column-edit__name"
                                             (str ".part-column-edit .classification-picker:has(input[name=" field "])")))
                header (s/bounds driver (str ".bulk-orient__columns > :nth-child(" column ")"))]
            (is (< (Math/abs (- (:x control) (:x header))) 1) (str field " aligns at " width))
            (is (< (Math/abs (- (:width control) (:width header))) 1))
            (is (<= (+ (:y control) (:height control)) (:y header))))))
      (s/fill-and-blur! driver "#part-column-bundle" "Bulk Fleet")
      (s/fill-and-blur! driver "#part-column-class" "Bulk Cruiser")
      (s/fill-and-blur! driver "#part-column-role" "bad/role")
      (s/click! driver "#part-column-update")
      (s/wait-visible! driver "#part-edit-status .detail__error")
      (is (= initial (catalog/snapshot! cat)) "An invalid field rejects the entire batch")
      (is (= "Bulk Fleet" (s/js driver "() => document.querySelector('#part-column-bundle').value")))
      (s/fill-and-blur! driver "#part-column-role" " ")
      (s/fill-and-blur! driver "#part-column-name" " ")
      (s/fill! driver "#bulk-orient-filters input[name=q]" "bridge")
      (is (s/wait-until #(= 1 (s/count-els driver ".bulk-orient__row"))))
      (is (= "2 selected" (s/text driver "[data-bulk-count]")))
      (s/wait-visible! driver "#part-column-update")
      (is (= "Bulk Fleet" (s/js driver "() => document.querySelector('#part-column-bundle').value")))
      (s/click! driver "#part-column-update")
      (is (s/wait-until #(every? (fn [id] (= ["Bulk Fleet" "Bulk Cruiser"]
                                             (mapv (catalog/summary! cat id) [:part/bundle :part/class]))) ids)))
      (doseq [id ids]
        (let [before (catalog/part initial id) after (catalog/summary! cat id)]
          (is (= (select-keys before [:part/name :part/role-hint :part/orientation :part/id :part/uid])
                 (select-keys after [:part/name :part/role-hint :part/orientation :part/id :part/uid])))))
      (s/click! driver "#bulk-orient-filters button:text-is('Clear selection')")
      (s/wait-visible! driver "[data-bulk-count]:text-is('0 selected')")
      (is (s/js driver "() => document.querySelector('#part-column-controls').hidden"))
      (finally (s/quit! driver) (fixture/stop! started)))))
