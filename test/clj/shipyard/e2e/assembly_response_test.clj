(ns shipyard.e2e.assembly-response-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout.db :as loadouts]
            [shipyard.e2e.support :as s])
  (:import [com.microsoft.playwright Page]))

(deftest immediately-saving-a-replaced-assembly-form-stays-in-the-app
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        ^Page page (:page driver)]
    (try
      ;; This transport contract must also work when the viewport fails to load.
      (.route page "**/js/viewport.js" (reify java.util.function.Consumer (accept [_ route] (.abort ^com.microsoft.playwright.Route route))))
      (s/go! driver (s/base-url sys))
      (s/open-assembly! driver)
      (s/select-option! driver ".assembly__hull select[name=part-id]" "hull")
      (s/click! driver ".assembly__hull button")
      (s/wait-visible! driver ".assembly__save")
      (is (s/wait-until #(zero? (s/count-els driver ".assembly__preparation"))))
      ;; Queue a user action during afterSwap, before the default 20ms HTMX settle.
      (s/js driver "() => { document.body.addEventListener('htmx:afterSwap', function save(e) { if(e.detail.target.id !== 'detail') return; document.body.removeEventListener('htmx:afterSwap', save); setTimeout(() => { const f=document.querySelector('.assembly__save'); f.elements.name.value='Quick save'; f.requestSubmit(); }, 0); }); }")
      (s/click! driver ".assembly__candidate[value='Synthetic Navy/Cruiser/prow']")
      (is (s/wait-until #(seq (:loadouts (loadouts/snapshot! (:shipyard.loadout/db sys))))))
      (is (= (str (s/base-url sys) "/") (.url page)))
      (is (= 1 (s/count-els driver "#workspace-navigation")))
      (is (= (:prow fixture/ids) (get-in (first (vals (:loadouts (loadouts/snapshot! (:shipyard.loadout/db sys))))) [:loadout/slots [[:prow 0]]])))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest editing-part-metadata-preserves-the-region-editor
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        ^Page page (:page driver)]
    (try
      (.route page "**/js/viewport.js" (reify java.util.function.Consumer (accept [_ route] (.abort ^com.microsoft.playwright.Route route))))
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "weapon")
      (s/wait-visible! driver ".part-metadata__form")
      (s/js driver "() => { window.originalRegionEditor=document.getElementById('part-regions'); window.originalRegionEditor.dataset.preservationProof='retained'; }")
      (s/fill-and-blur! driver ".part-metadata__form input[name=role]" "prow")
      (s/click! driver ".part-metadata__form button:text-is('Save metadata')")
      (is (s/wait-until #(= "prow" (s/js driver "() => document.querySelector('.part-metadata__form input[name=role]').value"))))
      (is (s/wait-until #(not (s/js driver "() => document.querySelector('.part-metadata__fields').disabled"))))
      (is (true? (s/js driver "() => document.getElementById('part-regions') === window.originalRegionEditor")))
      (is (= "retained" (s/js driver "() => document.getElementById('part-regions').dataset.preservationProof")))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest missed-scene-response-recovers-on-the-next-control-request
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        ^Page page (:page driver) dropped (atom false)]
    (try
      (s/go! driver (s/base-url sys))
      (s/open-assembly! driver)
      (s/select-option! driver ".assembly__hull select[name=part-id]" "hull")
      (s/click! driver ".assembly__hull button")
      (is (s/wait-until #(= (:hull fixture/ids) (:part-id (s/slot driver [])))))
      (.route page "**/assembly/assign"
              (reify java.util.function.Consumer
                (accept [_ route]
                  (.fetch ^com.microsoft.playwright.Route route)
                  (.abort ^com.microsoft.playwright.Route route)
                  (reset! dropped true))))
      (s/click! driver ".assembly__candidate[value='Synthetic Navy/Cruiser/prow-alt']")
      (is (s/wait-until #(deref dropped)))
      (is (= (:prow-alt fixture/ids) (get-in @(:state (:shipyard.assembly/db sys)) [:draft :assignments [[:prow 0]]])))
      (s/click! driver ".assembly__slot[data-slot='[[:prow 0]]'] > summary")
      (is (s/wait-until #(= (:prow-alt fixture/ids) (:part-id (s/slot driver [["prow" 0]])))))
      (finally (s/quit! driver) (fixture/stop! started)))))
