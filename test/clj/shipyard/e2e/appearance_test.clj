(ns shipyard.e2e.appearance-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.import-fixture :as archives]
            [shipyard.importer.db :as importer]
            [shipyard.settings.db :as settings])
  (:import [com.microsoft.playwright Page Page$EmulateMediaOptions Route]
           [com.microsoft.playwright.options ColorScheme]
           [java.util.function Consumer]))

(defn- system-theme! [{:keys [^Page page]} scheme]
  (.emulateMedia page (doto (Page$EmulateMediaOptions.) (.setColorScheme scheme))))

(defn- appearance! [driver label]
  (s/select-option! driver "#appearance-theme" label)
  (s/click! driver "#appearance-settings button")
  (is (s/wait-until #(= (.toLowerCase ^String label)
                        (s/js driver "() => document.querySelector('#app-appearance').dataset.theme"))))
  (s/wait-visible! driver "[role=status]:text-is('Saved appearance.')")
  (is (s/wait-until #(s/js driver "() => !document.querySelector('#appearance-settings button').disabled"))))

(defn- palette! [driver light?]
  (is (s/wait-until
       #(= (if light? ["rgb(237, 241, 245)" "rgb(29, 39, 51)" "light"]
               ["rgb(35, 42, 51)" "rgb(223, 228, 234)" "dark"])
           (s/js driver "() => {const s=getComputedStyle(document.querySelector('#appearance-theme'));return [s.backgroundColor,s.color,s.colorScheme]}")))
      "Both ordinary and native controls use the resolved palette")
  (is (= "rgb(20, 23, 28)"
         (s/js driver "() => getComputedStyle(document.querySelector('#viewport')).backgroundColor"))
      "The canvas stays dark even without the viewport bundle"))

(defn- background! [driver]
  (is (s/wait-until #(= "14171c" (:background (s/stats driver))))
      "The existing Three.js scene stays dark in every appearance mode"))

(defn- thumbnail! [driver]
  (s/wait-visible! driver ".part-thumbnail img")
  (is (s/wait-until #(s/js driver "() => {const i=document.querySelector('.part-thumbnail img');return i.complete&&i.naturalWidth>0}")))
  (is (= [0 "rgb(220, 227, 235)"]
         (s/js driver "() => {const i=document.querySelector('.part-thumbnail img'),c=document.createElement('canvas');c.width=i.naturalWidth;c.height=i.naturalHeight;const ctx=c.getContext('2d');ctx.drawImage(i,0,0);return [ctx.getImageData(0,0,1,1).data[3],getComputedStyle(i.parentElement).backgroundColor]}"))
      "PNG backgrounds are transparent and inherit the light thumbnail surface"))

(deftest light-dark-and-live-system-appearance-preserve-models
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        ^Page page (:page driver) database (:shipyard.store/db sys) id (:prow fixture/ids)]
    (try
      (system-theme! driver ColorScheme/LIGHT)
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "prow")
      (s/await-part driver id)
      (background! driver)
      (let [model (select-keys (s/stats driver) [:parts :materials :vertices :triangles :quaternion])]
        (s/click! driver "[data-workspace-mode=settings]")
        (s/wait-visible! driver "#appearance-theme")
        (palette! driver false)
        (.fill page "#cut-defaults [name=pit-depth]" "0.75")
        (.fill page "#settings-root" "Unsaved folder")
        (appearance! driver "Light")
        (is (= "0.75" (s/js driver "() => document.querySelector('#cut-defaults [name=pit-depth]').value")))
        (is (= "Unsaved folder" (s/js driver "() => document.querySelector('#settings-root').value")))
        (palette! driver true)
        (background! driver)
        (is (= :light (settings/theme! database)))
        (s/click! driver "[data-workspace-mode=browse]")
        (s/await-part driver id)
        (background! driver)
        (is (= model (select-keys (s/stats driver) (keys model))) "Changing appearance preserves geometry, material and pose")
        (s/screenshot-el! driver "body" (java.io.File. "/tmp/shipyard-light-theme.png"))
        (s/click! driver "[data-workspace-mode=settings]")
        (s/wait-visible! driver "#appearance-theme")
        (system-theme! driver ColorScheme/DARK)
        (palette! driver true)
        (appearance! driver "Dark")
        (palette! driver false)
        (system-theme! driver ColorScheme/LIGHT)
        (palette! driver false)
        (background! driver)
        (appearance! driver "Auto")
        (palette! driver true)
        (background! driver)
        (system-theme! driver ColorScheme/DARK)
        (palette! driver false)
        (background! driver)
        (system-theme! driver ColorScheme/LIGHT)
        (palette! driver true)
        (background! driver)
        (.reload page)
        (s/click! driver "[data-workspace-mode=settings]")
        (s/wait-visible! driver "#appearance-theme")
        (is (= "auto" (s/js driver "() => document.querySelector('#appearance-theme').value")))
        (palette! driver true)
        (s/click! driver "[data-workspace-mode=browse]")
        (s/await-part driver id)
        (background! driver)
        (s/click! driver "[data-part-back]")
        (thumbnail! driver)
        (s/screenshot-el! driver "body" (java.io.File. "/tmp/shipyard-light-browser.png")))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest appearance-works-without-the-viewport-bundle
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        ^Page page (:page driver)]
    (try
      (.route page "**/js/viewport.js" (reify Consumer (accept [_ route] (.abort ^Route route))))
      (system-theme! driver ColorScheme/LIGHT)
      (s/go! driver (s/base-url sys))
      (s/click! driver "[data-workspace-mode=settings]")
      (s/wait-visible! driver "#appearance-theme")
      (appearance! driver "Auto")
      (palette! driver true)
      (system-theme! driver ColorScheme/DARK)
      (palette! driver false)
      (.reload page)
      (s/click! driver "[data-workspace-mode=settings]")
      (s/wait-visible! driver "#appearance-theme")
      (is (= "auto" (s/js driver "() => document.querySelector('#appearance-theme').value")))
      (is (nil? (s/stats driver)))
      (palette! driver false)
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest changing-appearance-preserves-an-import-review
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        directory (fs/create-temp-dir) zip (archives/archive! directory)
        session! #(importer/session! {:workspace (:shipyard.workspace/db sys)})]
    (try
      (s/go! driver (s/base-url sys))
      (s/choose-path! driver ".import-start" zip)
      (s/wait-visible! driver ".import-review")
      (let [session (session!) listing (catalog/listing! (:catalog session))]
        (s/click! driver "[data-workspace-mode=settings]")
        (s/wait-visible! driver "#appearance-theme")
        (is (s/js driver "() => document.querySelector('#settings fieldset')?.disabled || document.querySelector('#settings').closest('fieldset').disabled"))
        (appearance! driver "Light")
        (palette! driver true)
        (s/click! driver "[data-workspace-mode=browse]")
        (s/wait-visible! driver ".import-review")
        (is (= session (session!)))
        (is (= listing (catalog/listing! (:catalog (session!)))))
        (s/click! driver "form[hx-post='/imports/cancel'] button")
        (s/wait-visible! driver ".import-start"))
      (finally (s/quit! driver) (fixture/stop! started) (fs/delete-tree directory)))))
