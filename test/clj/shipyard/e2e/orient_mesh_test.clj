(ns shipyard.e2e.orient-mesh-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.bulk-orientation.save-state :as saves]
            [shipyard.e2e.orient-save-test :as orient]
            [shipyard.e2e.support :as s]
            [shipyard.library.index :as index]
            [shipyard.part.orientation :as orientation])
  (:import [com.microsoft.playwright APIResponse Page Route Route$FulfillOptions]
           [com.microsoft.playwright.options LoadState]
           [java.util.function Consumer]))

(defn card [id] (str "[data-bulk-part='" id "']"))

(defn render! [driver started ids]
  (s/go! driver (s/base-url (:system started)))
  (s/click! driver ".masthead [data-workspace-mode='orient']")
  (s/wait-visible! driver "[data-bulk-select]")
  (doseq [id ids] (s/check! driver (str "[data-bulk-select][value='" id "']")))
  (s/click! driver "[data-bulk-render-button]"))

(defn target-request? [started id ^Route route]
  (let [key (index/mesh-key! (:shipyard.library/index (:system started)) id)]
    (and key (str/includes? (.url (.request route)) key))))

(deftest failed-downloads-and-payloads-have-local-retry
  (doseq [failure [:network :http :payload]]
    (testing (name failure)
      (let [started (fixture/start! true) driver (s/make-driver) ^Page page (:page driver)
            failed (:hull fixture/ids) healthy (:prow fixture/ids) failing? (atom true)
            q45 (orientation/from-euler-degrees 45 0 0)]
        (try
          (.route page "**/mesh/*.symesh"
                  (reify Consumer
                    (accept [_ value]
                      (let [^Route route value]
                        (if (and @failing? (target-request? started failed route))
                          (case failure
                            :network (.abort route)
                            :http (.fulfill route (doto (Route$FulfillOptions.) (.setStatus 503) (.setBody "unavailable")))
                            :payload (.fulfill route (doto (Route$FulfillOptions.) (.setStatus 200) (.setBody "not a mesh"))))
                          (.resume route))))))
          (render! driver started [failed healthy])
          (is (s/wait-until #(str/includes? (s/text driver (str (card failed) " [role=status]")) "Could not load")))
          (is (s/wait-until #(= 1 (get-in (s/stats driver) [:bulk :count]))))
          (s/wait-visible! driver (str (card failed) " [data-bulk-retry]"))
          (orient/set-yaw! driver 45)
          (is (= 1 (get-in (s/stats driver) [:bulk :dirty])))
          (reset! failing? false)
          (s/click! driver (str (card failed) " [data-bulk-retry]"))
          (is (s/wait-until #(= 2 (get-in (s/stats driver) [:bulk :count]))))
          (is (= "Ready" (s/text driver (str (card failed) " [role=status]"))))
          (is (saves/same-pose? q45 (orient/preview driver healthy)))
          (is (= 1 (get-in (s/stats driver) [:bulk :dirty])))
          (is (= "2 selected" (s/text driver "[data-bulk-grid-count]")))
          (is (nil? (orient/durable started healthy)))
          (finally (s/quit! driver) (fixture/stop! started)))))))

(deftest obsolete-grid-mesh-responses-cannot-install-models-or-errors
  (doseq [completion [:success :failure]]
    (let [started (fixture/start! true) driver (s/make-driver) ^Page page (:page driver)
          old (:hull fixture/ids) active (:prow fixture/ids) held (atom nil)]
      (try
        (.route page "**/mesh/*.symesh"
                (reify Consumer
                  (accept [_ value]
                    (let [^Route route value]
                      (if (and (nil? @held) (target-request? started old route))
                        (reset! held [route (.fetch route)])
                        (.resume route))))))
        (render! driver started [old active])
        (is (s/wait-until #(do (s/stats driver) (some? @held))))
        (is (s/wait-until #(= 1 (get-in (s/stats driver) [:bulk :count]))))
        (s/click! driver "[data-bulk-back]")
        (.uncheck page (str "[data-bulk-select][value='" old "']"))
        (s/click! driver "[data-bulk-render-button]")
        (is (s/wait-until #(= 1 (get-in (s/stats driver) [:bulk :count]))))
        (let [[^Route route ^APIResponse response] @held]
          (case completion
            :success (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response)))
            :failure (.abort route)))
        (.waitForLoadState page LoadState/NETWORKIDLE)
        (is (= 1 (get-in (s/stats driver) [:bulk :count])))
        (is (= 0 (s/count-els driver (card old))))
        (is (= "Ready" (s/text driver (str (card active) " [role=status]"))))
        (is (zero? (s/count-els driver "[data-mesh-state=failed]")))
        (finally (s/quit! driver) (fixture/stop! started))))))
