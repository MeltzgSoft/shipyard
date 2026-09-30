(ns shipyard.e2e.orient-context-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.bulk-orientation.save-state :as saves]
            [shipyard.e2e.orient-save-test :as orient]
            [shipyard.e2e.support :as s]
            [shipyard.part.orientation :as orientation])
  (:import [com.microsoft.playwright APIResponse Page Route Route$FulfillOptions]
           [java.util.function Consumer]
           [java.util.concurrent CountDownLatch ExecutorService TimeUnit]))

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

(deftest selection-response-preserves-pending-metadata-fields
  (let [system (s/start-system!) driver (s/make-driver)
        ^Page page (:page driver) held (atom nil)
        fields #(s/js driver "() => Object.fromEntries(new FormData(document.querySelector('.part-bulk-edit')))")
        expected {:field "name" :operation "prefix" :find "Prow" :value "Archived "}]
    (try
      (s/go! driver (s/base-url system))
      (s/wait-visible! driver "[data-bulk-select]")
      (.route page "**/orient/selection"
              (reify Consumer
                (accept [_ value]
                  (let [^Route route value]
                    (reset! held [route (.fetch route)])))))
      (s/check! driver (str "[data-bulk-select][value='" s/supported-id "']"))
      (is (s/wait-until #(do (s/text driver "[data-bulk-count]") (some? @held))))
      (testing "enabled fields can be edited while the real selection response is pending"
        (s/select-option! driver ".part-bulk-edit select[name=field]" "Name")
        (s/fill-and-blur! driver ".part-bulk-edit input[name=find]" "Prow")
        (s/select-option! driver ".part-bulk-edit select[name=operation]" "Add prefix")
        (s/fill! driver ".part-bulk-edit input[name=value]" "Archived ")
        (is (= expected (fields)))
        (is (true? (s/js driver "() => document.querySelector('.part-bulk-edit button').disabled"))))
      (let [[^Route route ^APIResponse response] @held]
        (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))
      (testing "selection updates the count and Apply button without replacing the user's edit"
        (is (s/wait-until #(= "1 selected" (s/text driver "[data-bulk-count]"))))
        (is (= expected (fields)))
        (is (= {:name "value" :start 9 :end 9}
               (s/js driver "() => ({name:document.activeElement.name,start:document.activeElement.selectionStart,end:document.activeElement.selectionEnd})")))
        (is (false? (s/js driver "() => document.querySelector('.part-bulk-edit button').disabled")))
        (s/click! driver ".part-bulk-edit button")
        (is (s/wait-until #(str/includes? (s/text driver "#bulk-orient-results") "Archived Supported Only Prow")))
        (is (= expected (fields)))
        (is (= "Human Navy Fleet Bundle"
               (s/text driver (str "[data-part-row='" s/supported-id "'] .bulk-orient__bundle")))))
      (.unroute page "**/orient/selection")
      (testing "clearing the selection disables Apply while retaining the editable fields"
        (.uncheck page (str "[data-bulk-select][value='" s/supported-id "']"))
        (is (s/wait-until #(= "0 selected" (s/text driver "[data-bulk-count]"))))
        (is (true? (s/js driver "() => document.querySelector('.part-bulk-edit button').disabled")))
        (is (= expected (fields))))
      (finally (s/quit! driver) (s/stop-system! system)))))

(deftest selection-waits-for-a-pending-workspace-table
  (doseq [viewport? [true false]]
    (testing (if viewport? "with viewport" "without viewport bundle")
      (let [system (s/start-system!) driver (s/make-driver)
            ^Page page (:page driver) held (atom nil)]
        (try
          (when-not viewport?
            (.route page "**/js/viewport.js" (reify Consumer (accept [_ route] (.abort ^Route route)))))
          (.route page "**/workspace/browse*"
                  (reify Consumer
                    (accept [_ value]
                      (let [^Route route value]
                        (reset! held [route (.fetch route)])))))
          (s/go! driver (s/base-url system))
          (is (s/wait-until #(do (s/js driver "() => document.readyState") (some? @held))))
          (is (zero? (s/count-els driver "[data-bulk-select]"))
              "the first table is not actionable before workspace restoration arrives")
          (let [[^Route route ^APIResponse response] @held]
            (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))
          (s/wait-visible! driver "[data-bulk-select]")
          (reset! held nil)
          (s/click! driver "[data-workspace-mode=browse]")
          (is (s/wait-until #(do (s/text driver "[data-bulk-count]") (some? @held))))
          (is (s/js driver "() => [...document.querySelectorAll('[data-bulk-select]')].every(e => e.disabled)")
              "selection cannot target a table that an accepted workspace transition will replace")
          (let [[^Route route ^APIResponse response] @held]
            (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))
          (doseq [id [s/hull-id s/prow-id]]
            (s/check! driver (str "[data-bulk-select][value='" id "']")))
          (is (s/wait-until #(= "2 selected" (s/text driver "[data-bulk-count]"))))
          (is (= 2 (s/count-els driver "[data-bulk-select]:checked")))
          (s/click! driver "[data-bulk-render-button]")
          (s/wait-visible! driver "[data-bulk-grid]")
          (is (= "2 selected" (s/text driver "[data-bulk-grid-count]")))
          (when viewport?
            (is (s/wait-until #(= 2 (get-in (s/stats driver) [:bulk :count])))))
          (finally (s/quit! driver) (s/stop-system! system)))))))
