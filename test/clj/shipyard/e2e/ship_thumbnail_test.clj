(ns shipyard.e2e.ship-thumbnail-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as classes]
            [shipyard.ship.db :as ships]
            [shipyard.e2e.support :as s])
  (:import [com.microsoft.playwright Page]))

(deftest lazy-assembled-and-painted-previews-without-viewport
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        ^Page page (:page driver) requests (atom [])
        class {:loadout/id (random-uuid) :loadout/name "Cruiser" :loadout/hull (:hull lf/draft) :loadout/slots lf/assignments}
        ship {:ship/id (random-uuid) :ship/name "Resolute" :ship/class (:loadout/id class)
              :ship/paint {:paint/layers {"Primary" {:base [1.0 0.0 0.0] :metalness 0.2 :roughness 0.6}}}}
        src #(s/js driver (str "() => document.querySelector('" % "').src"))]
    (try
      (.route page "**/js/viewport.js" (reify java.util.function.Consumer (accept [_ route] (.abort ^com.microsoft.playwright.Route route))))
      (.onRequest page (reify java.util.function.Consumer (accept [_ req] (swap! requests conj (.url ^com.microsoft.playwright.Request req)))))
      (classes/put! (:shipyard.loadout/db sys) class :create)
      (ships/put! (:shipyard.ship/db sys) ship :create)
      (s/go! driver (s/base-url sys))
      (s/ship-table! driver)
      (s/wait-visible! driver ".ship-table__class > .ship-table__row .ship-thumbnail img")
      (is (true? (s/js driver "() => document.querySelector('.ship-thumbnail img').naturalWidth === 128")))
      (is (not-any? #(.contains ^String % "/ship-thumbnails/ship/") @requests) "Collapsed named ships do not request thumbnails")
      (let [class-src (src ".ship-thumbnail img")
            draft @(:state (:shipyard.assembly/db sys))]
        (is (re-find #"/thumbnail-images/[0-9a-f]{64}$" class-src))
        (s/click! driver ".ship-card__ships summary")
        (s/wait-visible! driver ".ship-table__named .ship-thumbnail img")
        (is (not= class-src (src ".ship-table__named .ship-thumbnail img")))
        (is (= draft @(:state (:shipyard.assembly/db sys))) "Previewing leaves the draft untouched")
        (let [painted-src (src ".ship-table__named .ship-thumbnail img")]
          (ships/put! (:shipyard.ship/db sys) (assoc ship :ship/paint {}) :update)
          (s/fill! driver "#ship-filters input[name=q]" "Resolute")
          (is (s/wait-until #(and (pos? (s/count-els driver ".ship-table__named .ship-thumbnail img"))
                                  (not= painted-src (src ".ship-table__named .ship-thumbnail img")))))
          (is (= class-src (src ".ship-table__named .ship-thumbnail img"))))
        (s/go! driver (s/base-url sys))
        (s/wait-visible! driver ".ship-thumbnail img")
        (is (= class-src (src ".ship-thumbnail img")) "Reload reuses the immutable disk image URL")
        (s/screenshot-el! driver "#library" (java.io.File. "/tmp/shipyard-thumbnails.png")))
      (finally (s/quit! driver) (fixture/stop! started)))))
