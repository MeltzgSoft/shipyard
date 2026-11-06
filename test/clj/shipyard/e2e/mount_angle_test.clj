(ns shipyard.e2e.mount-angle-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.mount-angle-fixture :as curved]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.picked-face-test :as pick]
            [shipyard.e2e.support :as s]
            [shipyard.paint.faces :as faces])
  (:import [java.io File]
           [com.microsoft.playwright Page Route]
           [java.util.function Consumer]))

(deftest curved-selection-tolerance-save-and-reload
  (s/assert-bundle!)
  (let [started (fixture/start! true curved/build-library! (fn [_] nil))
        sys (:system started) cat (:shipyard.catalog/db sys) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "Ball joint") (s/await-part driver curved/id)
      (s/click! driver "[data-detail-tab=mounts]")
      (let [face (first (filter #(= (faces/face-key (first curved/triangles)) (:key %)) (:region-faces (s/stats driver))))
            bounds (s/bounds driver "#viewport")]
        (is (some? face))
        (s/click-point! driver (+ (:x bounds) (:x face)) (+ (:y bounds) (:y face))))
      (s/wait-visible! driver ".mount-wizard__form")
      (is (= 2 (count (get-in (s/stats driver) [:preview :facet-indices]))))
      (s/fill-and-blur! driver ".mount-wizard__form input[name=mount-id]" "ball-seat")
      (s/select-option! driver ".mount-wizard__form select[name=kind]" "plug")
      (testing "tolerance grows the curved highlight and preserves draft fields"
        (let [held (atom nil) ^Page page (:page driver)]
          (.route page "**/facet" (reify Consumer (accept [_ route] (reset! held route))))
          (s/fill-and-blur! driver "input[name=face-angle]" "20")
          ;; Playwright dispatches Java route callbacks during API calls.
          ;; Pump the page while waiting so this does not depend on .blur timing.
          (is (s/wait-until #(do (s/js driver "() => true") (some? @held))))
          (s/fill-and-blur! driver "input[name=mount-id]" "ball-seat-late")
          (.resume ^Route @held)
          (.unroute page "**/facet"))
        (is (s/wait-until #(= 12 (count (get-in (s/stats driver) [:preview :facet-indices])))))
        (is (= "ball-seat-late" (s/js driver "() => document.querySelector('input[name=mount-id]').value")))
        (is (= "plug" (s/js driver "() => document.querySelector('select[name=kind]').value"))))
      (s/screenshot-el! driver "#viewport" (File. "/tmp/shipyard-mount-angle.png"))
      (s/click! driver ".mount-wizard__actions button[value=create]")
      (is (s/wait-until #(nil? (:preview (s/stats driver)))))
      (let [mount (first (:part/mounts (:part (catalog/part-context! cat curved/id))))]
        (is (= 20.0 (get-in mount [:mount/facet :angle-deg])))
        (is (= :adjacent (get-in mount [:mount/facet :angle-rule])))
        (is (= 12 (count (get-in mount [:mount/facet :indices])))))
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "Ball joint") (s/await-part driver curved/id)
      (s/click! driver "[data-detail-tab=mounts]")
      (s/click! driver "form:has(input[name=mount-id][value=ball-seat-late]) button:text-is('Edit')")
      (s/wait-visible! driver "input[name=face-angle]")
      (is (= "20.0" (s/js driver "() => document.querySelector('input[name=face-angle]').value")))
      (is (s/wait-until #(= 12 (count (get-in (s/stats driver) [:preview :facet-indices])))))
      (s/fill-and-blur! driver "input[name=face-angle]" "10")
      (is (s/wait-until #(= 2 (count (get-in (s/stats driver) [:preview :facet-indices])))))
      (s/click! driver ".mount-wizard__actions button[value=update]")
      (is (s/wait-until #(nil? (:preview (s/stats driver)))))
      (is (= 2 (count (get-in (first (:part/mounts (:part (catalog/part-context! cat curved/id)))) [:mount/facet :indices]))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest dense-hemisphere-can-preview-save-and-reload
  (s/assert-bundle!)
  (let [triangles (curved/hemisphere)
        started (fixture/start! true #(curved/build-library! % triangles) (fn [_]))
        sys (:system started) cat (:shipyard.catalog/db sys) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "Ball joint") (s/await-part driver curved/id)
      (s/click! driver "[data-detail-tab=mounts]")
      (pick/pick! driver [0 5 0])
      (s/wait-visible! driver ".mount-wizard__form")
      (s/fill-and-blur! driver "input[name=face-angle]" "45")
      (is (> (count triangles) 12000))
      (is (s/wait-until #(= (count triangles) (get-in (s/stats driver) [:preview :triangles]))))
      (is (s/wait-until #(s/js driver "() => document.querySelector('#viewport-loading').hidden")))
      (s/fill-and-blur! driver "input[name=mount-id]" "hemisphere")
      (s/select-option! driver "select[name=kind]" "plug")
      (s/click! driver "input[name=face-edit]")
      (is (s/wait-until #(= "ready" (get-in (s/stats driver) [:preview :preparation]))))
      (is (s/wait-until #(s/js driver "() => document.querySelector('#viewport-loading').hidden")))
      (s/click! driver ".mount-wizard__actions button[value=create]")
      (is (s/wait-until #(nil? (:preview (s/stats driver)))))
      (let [saved (first (:part/mounts (:part (catalog/part-context! cat curved/id))))]
        (is (= (count triangles) (count (get-in saved [:mount/facet :indices])))))
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "Ball joint") (s/await-part driver curved/id)
      (s/click! driver "[data-detail-tab=mounts]")
      (s/click! driver "form:has(input[name=mount-id][value=hemisphere]) button:text-is('Edit')")
      (is (s/wait-until #(= (count triangles) (get-in (s/stats driver) [:preview :triangles]))))
      (is (= "45.0" (s/js driver "() => document.querySelector('input[name=face-angle]').value")))
      (is (s/wait-until #(s/js driver "() => document.querySelector('#viewport-loading').hidden")))
      (finally (s/quit! driver) (fixture/stop! started)))))
