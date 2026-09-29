(ns shipyard.e2e.orient-context-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.bulk-orientation.save-state :as saves]
            [shipyard.e2e.orient-save-test :as orient]
            [shipyard.e2e.support :as s]
            [shipyard.part.orientation :as orientation])
  (:import [java.util.concurrent CountDownLatch ExecutorService TimeUnit]))

(defn- assert-step! [driver step]
  (let [buttons (s/js driver "() => Array.from(document.querySelectorAll('[data-bulk-step]'), b => ({step:Number(b.dataset.bulkStep),pressed:b.getAttribute('aria-pressed'),background:getComputedStyle(b).backgroundColor}))")
        chosen (first (filter #(= step (:step %)) buttons))]
    (is (= 3 (count buttons)))
    (is (= "true" (:pressed chosen)))
    (doseq [button (remove #(= step (:step %)) buttons)]
      (is (= "false" (:pressed button)))
      (is (not= (:background chosen) (:background button))))))

(defn- turn! [driver id step]
  (s/click! driver "[data-bulk-reset]")
  (s/click! driver "[data-bulk-rotate][data-axis=y][data-direction='1']")
  (is (s/wait-until #(saves/same-pose? (orientation/from-euler-degrees step 0 0)
                                       (orient/preview driver id)))))

(deftest rotation-step-survives-preparation-rebuild-and-workspace-return
  (doseq [step [1 15 90]]
    (testing (str step " degree step")
      (let [started (fixture/start! true) driver (s/make-driver)
            id (:prow fixture/ids)
            release (CountDownLatch. 1) occupied (CountDownLatch. 2)
            ^ExecutorService pool (get-in started [:system :shipyard.http/jobs :pool])]
        (try
          ;; Hold real preprocessing so the browser necessarily sees a preparation poll.
          (dotimes [_ 2]
            (.submit pool ^Runnable (fn [] (.countDown occupied) (.await release))))
          (is (.await occupied 10 TimeUnit/SECONDS))
          (s/go! driver (s/base-url (:system started)))
          (s/wait-visible! driver "[data-bulk-select]")
          (s/check! driver (str "[data-bulk-select][value='" id "']"))
          (s/click! driver "[data-bulk-render-button]")
          (s/wait-visible! driver "[data-bulk-step]")
          (assert-step! driver 90)
          (s/click! driver (str "[data-bulk-step='" step "']"))
          (assert-step! driver step)
          (is (pos? (s/count-els driver "[data-preparing]")))
          (.countDown release)
          (is (s/wait-until #(= 1 (get-in (s/stats driver) [:bulk :count]))))
          (assert-step! driver step)
          (turn! driver id step)
          (s/click! driver "[data-bulk-back]")
          (s/wait-visible! driver "[data-bulk-render-button]")
          (s/click! driver "[data-bulk-render-button]")
          (is (s/wait-until #(= 1 (get-in (s/stats driver) [:bulk :count]))))
          (assert-step! driver step)
          (turn! driver id step)
          (s/click! driver "[data-workspace-mode=ships]")
          (s/wait-visible! driver "[data-ship-view=table]")
          (s/click! driver "[data-workspace-mode=browse]")
          (s/wait-visible! driver "[data-bulk-grid]")
          (assert-step! driver step)
          (turn! driver id step)
          (is (nil? (orient/durable started id)))
          (finally (.countDown release) (s/quit! driver) (fixture/stop! started)))))))
