(ns shipyard.e2e.variant-availability-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.orient-table-test :as table]
            [shipyard.e2e.support :as s]
            [shipyard.variant-availability-fixture :as parts])
  (:import [com.microsoft.playwright Page]))

(defn- row-ids [driver]
  (s/js driver "() => [...document.querySelectorAll('[data-part-row]')].map(e=>e.dataset.partRow)"))

(defn- availability [driver id]
  (s/js driver (format "() => [...document.querySelector(\"[data-part-row='%s']\").querySelectorAll('[data-variant]')].map(e=>e.textContent)" id)))

(defn- filter! [driver field choice expected]
  (s/select-option! driver (str "#bulk-orient-filters select[name=" field "]") choice)
  (is (s/wait-until #(= expected (row-ids driver)))))

(deftest variant-columns-filter-and-restore-the-published-table
  (s/assert-bundle!)
  (let [started (fixture/start! true parts/build! (fn [_])) sys (:system started) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver (table/row parts/all))
      (is (= ["Yes" "Yes" "Yes"] (availability driver parts/all)))
      (is (= ["Yes" "No" "Yes"] (availability driver parts/cut)))
      (is (= ["Yes" "No" "No"] (availability driver parts/plain)))
      (doseq [[width height] [[1600 1000] [900 800]]]
        (s/js driver "() => window.scrollTo(0,0)")
        (.setViewportSize ^Page (:page driver) width height)
        (s/scroll-into-view! driver (str (table/row parts/all) " [data-variant=unsupported-pitted]"))
        (is (s/js driver "() => {
          const headings=[...document.querySelector('.bulk-orient__columns').children];
          const cells=document.querySelector('[data-part-row]').querySelectorAll('[data-variant]');
          return [...cells].every((cell,i)=>{
            const h=headings[6+i].getBoundingClientRect(), c=cell.getBoundingClientRect();
            return Math.abs(h.x-c.x)<1 && Math.abs(h.width-c.width)<1 && c.width>=cell.scrollWidth;
          });
        }") "variant headings and availability cells align without clipping")
        (s/screenshot-el! driver "#library" (java.io.File. (str "/tmp/shipyard-219-table-" width ".png"))))
      (filter! driver "has-pitted" "Available" [parts/all parts/cut])
      (filter! driver "has-supported" "Missing" [parts/cut])
      (s/check! driver (str (table/row parts/cut) " [data-bulk-select]"))
      (s/wait-visible! driver "[data-bulk-count]:text-is('1 selected')")
      (.dblclick ^Page (:page driver) (str (table/row parts/cut) " > summary"))
      (s/wait-visible! driver "[data-part-back]")
      (is (some? (s/await-part driver parts/cut)))
      (is (s/js driver "() => document.querySelector('[data-part-nav=next]').disabled && document.querySelector('[data-part-nav=previous]').disabled")
          "individual navigation uses the availability-filtered cohort")
      (s/click! driver "[data-part-back]")
      (s/wait-visible! driver (table/row parts/cut))
      (is (= [parts/cut] (row-ids driver)))
      (is (= "available" (s/js driver "() => document.querySelector('[name=has-pitted]').value")))
      (is (= "missing" (s/js driver "() => document.querySelector('[name=has-supported]').value")))
      (s/click! driver "[data-workspace-mode=settings]")
      (s/wait-visible! driver "#settings")
      (s/click! driver "[data-workspace-mode=browse]")
      (s/wait-visible! driver (table/row parts/cut))
      (is (= [parts/cut] (row-ids driver)))
      (is (= "1 selected" (s/text driver "[data-bulk-count]")))
      (filter! driver "has-supported" "Any" [parts/all parts/cut])
      (filter! driver "has-pitted" "Any" [parts/all parts/cut parts/plain])
      (filter! driver "has-unsupported" "Missing" [parts/supported])
      (is (= ["No" "Yes" "No"] (availability driver parts/supported)))
      (filter! driver "has-pitted" "Available" [])
      (s/wait-visible! driver ".bulk-orient__empty:text-is('No parts match these filters.')")
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest saving-cuts-updates-table-availability-after-back-and-refresh
  (s/assert-bundle!)
  (let [started (fixture/start! true parts/build! (fn [_])) sys (:system started)
        cat (:shipyard.catalog/db sys) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver (table/row parts/plain))
      (is (= ["Yes" "No" "No"] (availability driver parts/plain)))
      (filter! driver "has-unsupported" "Available" [parts/all parts/cut parts/plain])
      (filter! driver "has-pitted" "Missing" [parts/plain])
      (s/open-prepared-part! driver sys "Plain" parts/plain)
      (s/click! driver "[data-detail-tab=mounts]")
      (let [{:keys [x y width height]} (s/bounds driver "#viewport")]
        (s/click-point! driver (+ x (/ width 2)) (+ y (/ height 2))))
      (s/wait-visible! driver ".mount-wizard__form")
      (s/select-option! driver "select[name=kind]" "socket")
      (s/click! driver "[name=create-pitted]")
      (s/fill-and-blur! driver "[name=cut-depth]" "0.1")
      (s/fill-and-blur! driver "[name=cut-diameter]" "0.2")
      (s/click! driver "button[value=create]")
      (is (s/wait-until #(= 1 (count (get-in (catalog/part-context! cat parts/plain) [:part :part/mounts])))))
      (is (s/wait-until #(= 1 (count (get-in (s/stats driver) [:interfaces :cuts])))))
      (s/click! driver "[data-part-back]")
      (s/wait-visible! driver ".bulk-orient__empty:text-is('No parts match these filters.')")
      (is (empty? (row-ids driver)))
      (filter! driver "has-pitted" "Available" [parts/all parts/cut parts/plain])
      (is (= ["Yes" "No" "Yes"] (availability driver parts/plain)))
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver (table/row parts/plain))
      (is (= ["Yes" "No" "Yes"] (availability driver parts/plain)))
      (is (= "available" (s/js driver "() => document.querySelector('[name=has-pitted]').value")))
      (s/open-prepared-part! driver sys "Plain" parts/plain)
      (is (s/wait-until #(= 1 (count (get-in (s/stats driver) [:interfaces :cuts])))))
      (finally (s/quit! driver) (fixture/stop! started)))))
