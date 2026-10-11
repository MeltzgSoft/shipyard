(ns shipyard.e2e.filter-sidebar-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.import-fixture :as archives]
            [shipyard.loadout.db :as classes])
  (:import [com.microsoft.playwright Page]))

(defn- assert-filter-order! [driver form clear?]
  (is (= "q" (s/js driver (str "() => document.querySelector('" form "').querySelector('input,select').name"))))
  (when clear?
    (is (s/js driver (str "() => document.querySelector('" form "').lastElementChild.matches('[data-filter-clear]')")))))

(defn- leave-sidebar! [driver]
  (.hover ^Page (:page driver) "#workspace-navigation"))

(defn- open? [driver id]
  (s/js driver (str "() => document.getElementById('" id "').open")))

(defn- toggle-sidebar! [driver id content]
  (let [sidebar (str "#" id) page ^Page (:page driver)]
    (leave-sidebar! driver)
    (is (false? (open? driver id)))
    (s/wait-visible! driver content)
    (let [collapsed (s/width driver content)]
      (.hover page (str sidebar " > summary"))
      (is (open? driver id))
      (is (< (s/width driver content) collapsed))
      (is (s/js driver (str "() => document.querySelector('" sidebar "').getBoundingClientRect().right <= document.querySelector('" content "').getBoundingClientRect().left + 1")))
      (leave-sidebar! driver)
      (is (false? (open? driver id)))
      (.focus page (str sidebar " > summary"))
      (.press page (str sidebar " > summary") "Enter")
      (is (open? driver id) "The collapsed sidebar can be opened by keyboard")
      (s/click! driver (str sidebar " [data-filter-pin]"))
      (leave-sidebar! driver)
      (is (open? driver id) "Pinning keeps the sidebar open after pointer exit")
      (is (= "true" (s/js driver (str "() => document.querySelector('" sidebar " [data-filter-pin]').getAttribute('aria-pressed')"))))
      (s/click! driver (str sidebar " [data-filter-pin]"))
      (leave-sidebar! driver)
      (is (false? (open? driver id)))
      (is (= collapsed (s/width driver content))))))

(deftest part-filters-and-selection-survive-sidebar-toggling-and-navigation
  (let [started (fixture/start! true) driver (s/make-driver)
        workspace (:state (:shipyard.workspace/db (:system started)))]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver "[data-bulk-select]")
      (assert-filter-order! driver "#bulk-orient-filters" true)
      (is (false? (open? driver "part-filter-sidebar")) "Filters start collapsed")
      (s/check! driver (str "[data-part-row='" (:prow fixture/ids) "'] [data-bulk-select]"))
      (s/wait-visible! driver "[data-bulk-count]:text-is('1 selected')")
      (s/fill! driver "#bulk-orient-filters input[name=q]" "prow")
      (is (s/wait-until #(= 2 (s/count-els driver "[data-part-row]"))))
      (let [before @workspace]
        (toggle-sidebar! driver "part-filter-sidebar" "#bulk-orient-results")
        (is (= before @workspace) "Toggling performs no server filtering or selection operation"))
      (s/open-part! driver "prow")
      (s/await-part driver (:prow fixture/ids))
      (doseq [tab ["part" "mounts" "regions"]]
        (s/click! driver (str "[data-detail-tab=" tab "]"))
        (is (zero? (s/count-els driver "#detail [data-filter-sidebar], #detail [data-filter-pin]"))
            "Individual Part Browser inspector tabs contain no filter sidebar"))
      (s/click! driver "[data-part-back]")
      (s/wait-visible! driver "#bulk-orient-filters input[name=q]")
      (is (= "prow" (s/js driver "() => document.querySelector('#bulk-orient-filters input[name=q]').value")))
      (is (= "1 selected" (s/text driver "[data-bulk-count]")))
      (s/click! driver "[data-filter-clear]")
      (is (s/wait-until #(> (s/count-els driver "[data-part-row]") 2)))
      (is (= "1 selected" (s/text driver "[data-bulk-count]")))
      (s/click! driver "#part-filter-sidebar [data-filter-pin]")
      (s/click! driver "[data-workspace-mode=settings]")
      (s/wait-visible! driver "#settings-workspace")
      (s/click! driver "[data-workspace-mode=browse]")
      (s/wait-visible! driver "[data-bulk-select]")
      (assert-filter-order! driver "#bulk-orient-filters" true)
      (is (open? driver "part-filter-sidebar") "Pinned presentation survives a workspace round trip")
      (is (= "1 selected" (s/text driver "[data-bulk-count]")))
      (s/click! driver "#part-filter-sidebar [data-filter-pin]")
      (testing "The sidebar scrolls independently and can still collapse on a small window"
        (s/resize! driver 640 480)
        (s/scroll-into-view! driver "[data-filter-clear]")
        (s/click! driver "#part-filter-sidebar > summary")
        (s/wait-visible! driver "[data-part-row]"))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest import-review-uses-the-same-filter-sidebar
  (let [started (fixture/start! true) driver (s/make-driver)
        zip (archives/archive! (:temp started))]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver ".import-start")
      (s/choose-path! driver ".import-start" zip)
      (s/wait-visible! driver ".import-review")
      (s/wait-visible! driver "[data-bulk-select]")
      (assert-filter-order! driver "#bulk-orient-filters" true)
      (s/check! driver "#part-select-matching")
      (s/wait-visible! driver "[data-bulk-count]:text-is('2 selected')")
      (s/fill! driver "#bulk-orient-filters input[name=q]" "Hull")
      (is (s/wait-until #(= 1 (s/count-els driver "[data-part-row]"))))
      (toggle-sidebar! driver "part-filter-sidebar" "#bulk-orient-results")
      (is (= "2 selected" (s/text driver "[data-bulk-count]")))
      (s/click! driver "[data-filter-clear]")
      (is (s/wait-until #(= 2 (s/count-els driver "[data-part-row]"))))
      (s/click! driver "form[hx-post='/imports/cancel'] button")
      (s/wait-visible! driver ".import-start")
      (finally (s/quit! driver) (fixture/stop! started)))))

(defn- assert-inline-hull-filters! [driver]
  (s/wait-visible! driver ".assembly__filters select[name=bundle]")
  (s/wait-visible! driver ".assembly__filters select[name=class]")
  (is (zero? (s/count-els driver "#detail [data-filter-sidebar], #detail [data-filter-pin]")))
  (is (s/js driver "() => { const filters = document.querySelector('.assembly__filters').getBoundingClientRect(); const hull = document.querySelector('.assembly__hull').getBoundingClientRect(); return filters.bottom <= hull.top + 1 && Math.abs(filters.width - hull.width) < 1; }")
      "Filters remain visible above the hull picker at the same width")
  (is (<= (s/width driver ".stage__detail") 390) "The floating inspector retains its original width"))

(defn- filter-hulls! [driver workspace field label value count]
  (s/select-option! driver (str ".assembly__filters select[name=" field "]") label)
  (is (s/wait-until #(and (= value (get-in @workspace [:workspaces :ships :assembly-filters field]))
                          (s/js driver "() => !document.querySelector('.assembly__filters.htmx-request')"))))
  (is (= value (s/js driver (str "() => document.querySelector('.assembly__filters select[name=" field "]').value"))))
  (is (= count (s/count-els driver ".assembly__hull select[name=part-id] option[value]:not([value=''])")))
  (assert-inline-hull-filters! driver))

(deftest ship-table-sidebar-and-inline-inspector-filters
  (let [started (fixture/start! true) driver (s/make-driver) sys (:system started)
        class-db (:shipyard.loadout/db sys)
        workspace (:state (:shipyard.workspace/db sys))
        assembly (:state (:shipyard.assembly/db sys))]
    (try
      (catalog/save-metadata! (:shipyard.catalog/db sys)
                              [{:id (:hint fixture/ids) :attribute :part/bundle-override :value "Other Navy"}
                               {:id (:hint fixture/ids) :attribute :part/class-override :value "Corvette"}])
      (doseq [name ["Cruiser" "Escort"]]
        (classes/put! class-db {:loadout/id (random-uuid) :loadout/name name
                                :loadout/hull (:hull fixture/ids) :loadout/slots {}} :create))
      (s/go! driver (s/base-url sys))
      (s/ship-table! driver)
      (assert-filter-order! driver "#ship-filters" false)
      (s/fill! driver "#ship-filters input[name=q]" "Cruiser")
      (is (s/wait-until #(= 1 (s/count-els driver ".ship-card"))))
      (let [before @workspace]
        (toggle-sidebar! driver "ship-filter-sidebar" "#ship-results")
        (is (= before @workspace)))
      (.dblclick ^Page (:page driver) ".ship-table__row[aria-label='Open class Cruiser']")
      (s/await-assembly-prepared! driver 1)
      (let [before (:draft @assembly) parts (:parts (s/stats driver))]
        (testing "Inline filters narrow hull choices without changing the draft or viewport"
          (assert-inline-hull-filters! driver)
          (filter-hulls! driver workspace "bundle" "Synthetic Navy" "Synthetic Navy" 1)
          (filter-hulls! driver workspace "class" "Cruiser" "Cruiser" 1)
          (filter-hulls! driver workspace "bundle" "Other Navy" "Other Navy" 0)
          (is (s/js driver "() => document.querySelector('.assembly__hull select[name=part-id]').disabled"))
          (filter-hulls! driver workspace "bundle" "Synthetic Navy" "Synthetic Navy" 1)
          (filter-hulls! driver workspace "class" "Corvette" "Corvette" 0)
          (filter-hulls! driver workspace "class" "Cruiser" "Cruiser" 1)
          (is (= before (:draft @assembly)))
          (is (= parts (:parts (s/stats driver))))))
      (testing "Inline layout fits a smaller window"
        (s/resize! driver 640 700)
        (assert-inline-hull-filters! driver)
        (s/resize! driver 1280 900))
      (testing "No ship inspector tab contains a filter sidebar"
        (doseq [tab ["schemes" "paint" "assembly"]]
          (s/click! driver (str ".ship-inspector > nav button[hx-get='/ships/tab/" tab "']"))
          (s/wait-visible! driver (str ".ship-inspector[data-ship-inspector-tab=" tab "]"))
          (is (zero? (s/count-els driver "#detail [data-filter-sidebar], #detail [data-filter-pin]"))))
        (assert-inline-hull-filters! driver)
        (is (= "Synthetic Navy" (s/js driver "() => document.querySelector('.assembly__filters select[name=bundle]').value")))
        (is (= "Cruiser" (s/js driver "() => document.querySelector('.assembly__filters select[name=class]').value"))))
      (s/click! driver "[data-workspace-mode=browse]")
      (s/wait-visible! driver "#bulk-orient-results")
      (s/click! driver "[data-workspace-mode=ships]")
      (assert-inline-hull-filters! driver)
      (is (= "Synthetic Navy" (s/js driver "() => document.querySelector('.assembly__filters select[name=bundle]').value")))
      (is (= "Cruiser" (s/js driver "() => document.querySelector('.assembly__filters select[name=class]').value")))
      (s/click! driver "[data-ship-back]")
      (s/wait-visible! driver "#ship-filters")
      (is (= "Cruiser" (s/js driver "() => document.querySelector('#ship-filters input[name=q]').value")))
      (is (= 1 (s/count-els driver ".ship-card")))
      (finally (s/quit! driver) (fixture/stop! started)))))
