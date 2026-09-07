(ns shipyard.e2e.assembly-panel-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s])
  (:import [com.microsoft.playwright Page Route]
           [java.util.function Consumer]))

(defn- await-ready! [driver]
  (s/wait-until #(zero? (s/count-els driver ".assembly__preparation"))))

(defn- slot-selector [path]
  (str ".assembly__slot[data-slot='" (pr-str path) "']"))

(defn- assign! [driver path label]
  (await-ready! driver)
  (let [selector (slot-selector path)]
    (s/select-option! driver (str selector " select[name=part-id]") label)
    (s/click! driver (str selector " form[action='/assembly/assign'] button"))
    (s/wait-until #(str/includes? (s/text driver (str selector " .assembly__current")) label))))

(defn- workflow! [blocked?]
  (let [started (fixture/start! true)
        driver (s/make-driver)]
    (try
      (when blocked?
        (.route ^Page (:page driver) "**/js/viewport.js"
                (reify Consumer (accept [_ route] (.abort ^Route route)))))
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver "#library-results .part")
      (s/js driver "() => { window.assemblyCanvas = document.querySelector('#viewport'); }")
      (s/click! driver ".masthead a:has-text('Assembly')")
      (s/wait-visible! driver "#assembly")
      (s/click! driver ".assembly__hull button")
      (s/wait-visible! driver (slot-selector [[:weapon 0]]))
      (testing "capacity-two choices and nested turrets are server rendered"
        (assign! driver [[:weapon 0]] "weapon")
        (assign! driver [[:weapon 1]] "weapon")
        (s/wait-visible! driver (slot-selector [[:weapon 0] [:turret 0]]))
        (assign! driver [[:weapon 0] [:turret 0]] "turret")
        (is (= 2 (s/count-els driver (str (slot-selector [[:weapon 0]]) " option"))))
        (is (not (str/includes? (s/text driver (str (slot-selector [[:weapon 0]]) " select")) "hint")))
        (is (not (str/includes? (s/text driver (str (slot-selector [[:weapon 0]]) " select")) "supported"))))
      (testing "replace/clear remove descendants and preserve the canvas"
        (assign! driver [[:weapon 0]] "weapon-alt")
        (is (= "Empty" (s/text driver (str (slot-selector [[:weapon 0] [:turret 0]]) " .assembly__current"))))
        (s/click! driver (str (slot-selector [[:weapon 0]]) " form[action='/assembly/clear'] button"))
        (is (s/wait-until #(zero? (s/count-els driver (slot-selector [[:weapon 0] [:turret 0]])))))
        (is (true? (s/js driver "() => window.assemblyCanvas === document.querySelector('#viewport')"))))
      (testing "stale revision shows a recoverable error in HTML"
        (await-ready! driver)
        (s/js driver "() => { document.querySelector('.assembly__hull input[name=revision]').value = '0'; }")
        (s/click! driver ".assembly__hull button")
        (is (s/wait-until #(str/includes? (s/text driver "#assembly") "draft changed"))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest slot-panel-workflow
  (workflow! false))

(deftest slot-panel-without-viewport
  (workflow! true))
