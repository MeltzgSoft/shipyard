(ns shipyard.e2e.paint-preparation-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.named-ship-test :as named]
            [shipyard.fixtures :as meshes]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as loadouts])
  (:import [com.microsoft.playwright Playwright BrowserType$LaunchOptions Browser$NewPageOptions]))

(defn detailed-library! [root]
  (fixture/library! root)
  (with-open [output (io/output-stream (fs/file root (:hull fixture/ids) "unsupported.stl"))]
    (.write output ^bytes (meshes/->binary-stl (meshes/uv-sphere 6.0 96 192))))
  root)

(defn profile-driver! []
  (if (= "firefox" (System/getProperty "shipyard.paint.profile.browser"))
    (let [pw (Playwright/create)
          browser (.launch (.firefox pw) (doto (BrowserType$LaunchOptions.) (.setHeadless true)))]
      {:playwright pw :browser browser :page (.newPage browser (doto (Browser$NewPageOptions.) (.setViewportSize 1280 900)))})
    (s/make-driver)))

(deftest detailed-assembly-paint-preparation-yields-and-reuses-source-topology
  (s/assert-bundle!)
  (let [started (fixture/start! true detailed-library!) sys (:system started) driver (profile-driver!)
        class {:loadout/id (random-uuid) :loadout/name "Detailed cruiser" :loadout/hull (:hull fixture/ids) :loadout/slots lf/assignments}]
    (try
      (loadouts/put! (:shipyard.loadout/db sys) class :create)
      (s/go! driver (s/base-url sys))
      (s/js driver "() => { window.paintTicks=0;window.paintLast=performance.now();window.paintMaxGap=0;window.paintTimer=setInterval(()=>{const t=performance.now();window.paintMaxGap=Math.max(window.paintMaxGap,t-window.paintLast);window.paintLast=t;window.paintTicks++;},10); }")
      (s/open-class! driver "Detailed cruiser")
      (is (s/wait-until #(some :paint-preparing (get-in (s/stats driver) [:assembly :slots])) 30000))
      (let [ticks (s/js driver "() => window.paintTicks")]
        (named/tab! driver "Schemes")
        (s/fill-and-blur! driver "#scheme-create input[name=name]" "Responsive fleet")
        (s/click! driver "#scheme-create button")
        (s/wait-visible! driver "#scheme-material")
        (s/input! driver "#scheme-material input[name=base]" "#0088ff" "input")
        (is (= "#0088ff" (s/js driver "() => document.querySelector('#scheme-material').elements.base.value")))
        (is (> (s/js driver "() => window.paintTicks") ticks) "Input and timer tasks run while the detailed hull's buffers prepare"))
      (is (s/wait-until #(and (= 13 (count (get-in (s/stats driver) [:assembly :slots])))
                              (every? (complement :paint-preparing) (get-in (s/stats driver) [:assembly :slots]))) 30000))
      (let [cold (:paint-preparation (s/slot driver []))
            urls (s/js driver "() => performance.getEntriesByType('resource').filter(e=>e.name.includes('/paint/preparation/topology')).map(e=>e.name)")]
        (testing "instances share topology and palette changes keep source buffers installed"
          (is (every? :prepared-topology (get-in (s/stats driver) [:assembly :slots])))
          (is (<= (count urls) 2) "Detailed hull and shared small component mesh each prepare once")
          (let [uuid (:uuid (s/slot driver []))]
            (s/input! driver "#scheme-material input[name=metalness]" "0.7" "input")
            (is (s/wait-until #(not (:paint-preparing (s/slot driver []))) 30000))
            (is (= uuid (:uuid (s/slot driver []))))
            (is (= 0.7 (:metalness (s/slot driver []))))))
        (let [report {:browser (or (System/getProperty "shipyard.paint.profile.browser") "chromium")
                      :triangles 36480 :cold cold :warm (:paint-preparation (s/slot driver []))
                      :event-loop-max-gap-ms (s/js driver "() => window.paintMaxGap") :topology-requests (count urls)}]
          (spit "/tmp/shipyard-paint-profile.edn" (pr-str report))
          (println "Paint preparation profile:" (pr-str report))))
      (finally (s/quit! driver) (fixture/stop! started)))))
