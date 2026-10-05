(ns shipyard.e2e.part-navigation-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.orient-table-test :as table]
            [shipyard.part-navigation-fixture :as parts]
            [shipyard.workspace.db :as workspace])
  (:import [com.microsoft.playwright APIResponse Dialog Page Route Route$FulfillOptions]
           [java.util.function Consumer]))

(deftest neighbors-confirm-drafts-and-include-unloaded-results
  (s/assert-bundle!)
  (let [started (fixture/start! true parts/build! (fn [_])) sys (:system started) driver (s/make-driver)
        ^Page page (:page driver) cat (:shipyard.catalog/db sys) ws (:shipyard.workspace/db sys)
        dialogs (atom 0) accept? (atom true)
        listener (reify Consumer (accept [_ dialog]
                                   (swap! dialogs inc)
                                   (if @accept? (.accept ^Dialog dialog) (.dismiss ^Dialog dialog))))
        next! #(s/click! driver "[data-part-nav=next]")
        previous! #(s/click! driver "[data-part-nav=previous]")
        at! #(s/await-part driver (parts/id %))]
    (.onDialog page listener)
    (try
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver ".bulk-orient__row")
      (s/fill! driver "#bulk-orient-filters input[name=q]" "Filtered")
      (s/wait-visible! driver ".results__count:text-is('65 matches')")
      (is (= 50 (s/count-els driver ".bulk-orient__row")))
      (.dblclick page (str (table/row (parts/id 0)) " .bulk-orient__part"))
      (at! 0)
      (is (s/js driver "() => document.querySelector('[data-part-nav=previous]').disabled"))
      (next!) (at! 1) (previous!) (at! 0)
      (is (zero? @dialogs))
      (testing "canceling metadata navigation retains the browser and server draft context"
        (s/fill-and-blur! driver ".part-metadata__form input[name=name]" "Unsaved battery")
        (let [context (workspace/active-context! ws)]
          (reset! accept? false) (next!)
          (is (= 1 @dialogs))
          (is (= context (workspace/active-context! ws)))
          (is (= "Unsaved battery" (s/js driver "() => document.querySelector('.part-metadata__form input[name=name]').value")))
          (is (= (parts/id 0) (:selection (workspace/workspace! ws :browse)))))
        (reset! accept? true) (next!) (at! 1) (previous!) (at! 0)
        (is (= "Filtered 00" (:part/name (:part (catalog/part-context! cat (parts/id 0)))))))
      (testing "a pending metadata save locks both navigation controls, then leaves a clean editor"
        (let [held (atom nil) count @dialogs]
          (s/fill-and-blur! driver ".part-metadata__form input[name=name]" "Filtered 00 saved")
          (.route page "**/parts/metadata/individual"
                  (reify Consumer (accept [_ route] (reset! held [route (.fetch ^Route route)]))))
          (s/click! driver ".part-metadata__form button:text-is('Save metadata')")
          (is (s/wait-until #(do (s/js driver "() => document.readyState") (some? @held))))
          (is (s/js driver "() => [...document.querySelectorAll('[data-part-nav], [data-part-back]')].every(e=>e.disabled)"))
          (let [[^Route route ^APIResponse response] @held]
            (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))
          (.unroute page "**/parts/metadata/individual")
          (is (s/wait-until #(s/js driver "() => !document.querySelector('[data-part-nav=next]').disabled")))
          (next!) (at! 1) (previous!) (at! 0)
          (is (= count @dialogs))))
      (testing "orientation previews require confirmation and saves clear it"
        (s/fill-and-blur! driver ".part-orientation__form input[name=part-yaw-deg]" "45")
        (is (s/wait-until #(> (abs (second (:orientation (s/stats driver)))) 0.3)))
        (let [before (:orientation (s/stats driver))]
          (reset! accept? false) (next!)
          (is (= before (:orientation (s/stats driver)))))
        (s/click! driver ".part-orientation__actions button[value=save]")
        (is (s/wait-until #(some? (:part/orientation (:part (catalog/part-context! cat (parts/id 0)))))))
        (let [count @dialogs]
          (next!) (at! 1)
          (is (= count @dialogs)))
        (previous!) (at! 0))
      (testing "an unsaved picked mount survives Cancel and is discarded only after confirmation"
        (s/click! driver "[data-detail-tab=mounts]")
        (let [target (first (filter :visible? (:region-faces (s/stats driver))))
              target (or target (first (:region-faces (s/stats driver))))
              bounds (s/bounds driver "#viewport")]
          (s/click-point! driver (+ (:x bounds) (:x target)) (+ (:y bounds) (:y target))))
        (s/wait-visible! driver ".mount-wizard__form")
        (reset! accept? false)
        (let [preview (:preview (s/stats driver))]
          (next!)
          (is (= preview (:preview (s/stats driver)))))
        (reset! accept? true) (next!) (at! 1)
        (is (empty? (:part/mounts (:part (catalog/part-context! cat (parts/id 0))))))
        (is (nil? (:preview (s/stats driver)))))
      (testing "Next reaches beyond the unloaded table batch and the final boundary"
        (doseq [n (range 2 65)] (next!) (at! n))
        (is (s/js driver "() => document.querySelector('[data-part-nav=next]').disabled"))
        (is (= (parts/id 64) (:selection (workspace/workspace! ws :browse))))
        (s/screenshot-el! driver ".detail__navigation" (java.io.File. "/tmp/shipyard-part-navigation.png"))
        (s/click! driver "[data-part-back]")
        (s/wait-visible! driver (table/row (parts/id 0)))
        (is (= "Filtered" (s/js driver "() => document.querySelector('#bulk-orient-filters input[name=q]').value"))))
      (finally (.offDialog page listener) (s/quit! driver) (fixture/stop! started)))))
