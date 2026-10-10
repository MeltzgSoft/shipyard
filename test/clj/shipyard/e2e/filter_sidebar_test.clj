(ns shipyard.e2e.filter-sidebar-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.import-fixture :as archives]
            [shipyard.loadout.db :as classes])
  (:import [com.microsoft.playwright Page]))

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

(deftest ship-table-and-hull-picker-use-filter-sidebars
  (let [started (fixture/start! true) driver (s/make-driver) sys (:system started)
        class-db (:shipyard.loadout/db sys)
        workspace (:state (:shipyard.workspace/db sys))
        assembly (:state (:shipyard.assembly/db sys))]
    (try
      (doseq [name ["Cruiser" "Escort"]]
        (classes/put! class-db {:loadout/id (random-uuid) :loadout/name name
                                :loadout/hull (:hull fixture/ids) :loadout/slots {}} :create))
      (s/go! driver (s/base-url sys))
      (s/ship-table! driver)
      (s/fill! driver "#ship-filters input[name=q]" "Cruiser")
      (is (s/wait-until #(= 1 (s/count-els driver ".ship-card"))))
      (let [before @workspace]
        (toggle-sidebar! driver "ship-filter-sidebar" "#ship-results")
        (is (= before @workspace)))
      (.dblclick ^Page (:page driver) ".ship-table__row[aria-label='Open class Cruiser']")
      (s/await-assembly-prepared! driver 1)
      (s/select-option! driver ".assembly__filters select[name=bundle]" "Synthetic Navy")
      (s/wait-visible! driver ".assembly__filters select[name=class]")
      (s/click! driver "#assembly-filter-sidebar [data-filter-pin]")
      (s/select-option! driver ".assembly__filters select[name=class]" "Cruiser")
      (is (s/wait-until #(= "Cruiser" (get-in @workspace [:workspaces :ships :assembly-filters "class"]))))
      (is (s/wait-until #(s/js driver "() => !document.querySelector('.assembly__filters.htmx-request') && !!document.querySelector('#assembly-filter-sidebar [data-filter-pin][aria-pressed=true]')")))
      (leave-sidebar! driver)
      (is (open? driver "assembly-filter-sidebar") "Pin survives the hull-filter fragment replacement")
      (s/click! driver "#assembly-filter-sidebar [data-filter-pin]")
      (let [before (:draft @assembly) parts (:parts (s/stats driver))]
        (toggle-sidebar! driver "assembly-filter-sidebar" "#assembly-rail .filter-layout__content")
        (is (= before (:draft @assembly)))
        (is (= parts (:parts (s/stats driver)))))
      (s/click! driver "[data-ship-back]")
      (s/wait-visible! driver "#ship-filters")
      (is (= "Cruiser" (s/js driver "() => document.querySelector('#ship-filters input[name=q]').value")))
      (is (= 1 (s/count-els driver ".ship-card")))
      (finally (s/quit! driver) (fixture/stop! started)))))
