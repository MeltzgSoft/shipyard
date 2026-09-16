(ns shipyard.e2e.orient-save-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.bulk-orientation.save-state :as saves]
            [shipyard.catalog.db :as catalog]
            [shipyard.catalog.sidecar :as sidecar]
            [shipyard.e2e.support :as s]
            [shipyard.part.orientation :as orientation])
  (:import [com.microsoft.playwright APIResponse Page Route Route$FulfillOptions]
           [java.util.function Consumer]))

(defn open-grid! [driver started ids]
  (s/go! driver (s/base-url (:system started)))
  (s/click! driver ".masthead [data-workspace-mode='orient']")
  (s/wait-visible! driver "[data-bulk-select]")
  (doseq [id ids] (s/check! driver (str "[data-bulk-select][value='" id "']")))
  (s/click! driver "[data-bulk-render-button]")
  (when-not (s/wait-until #(= (count ids) (get-in (s/stats driver) [:bulk :count])))
    (throw (ex-info "Selected Orient previews did not finish loading; save assertions cannot run"
                    {:selected ids :bulk (:bulk (s/stats driver))
                     :grid (s/text driver "[data-bulk-grid]")}))))

(defn set-yaw! [driver degrees]
  (s/fill-and-blur! driver "[data-bulk-angle][data-axis=y]" (str degrees)))

(defn preview [driver id]
  (get-in (s/stats driver) [:bulk :orientations (keyword id)]))

(defn durable [started id]
  (:part/orientation (catalog/part (catalog/snapshot! (:shipyard.catalog/db (:system started))) id)))

(defn save! [driver]
  (s/click! driver "[data-bulk-save-button]"))

(deftest delayed-save-acknowledges-only-the-submitted-pose
  (let [started (fixture/start! true) driver (s/make-driver) ^Page page (:page driver)
        held (atom nil) id (:prow fixture/ids)
        q45 (orientation/from-euler-degrees 45 0 0) q90 (orientation/from-euler-degrees 90 0 0)]
    (try
      (open-grid! driver started [id])
      (.route page "**/orient/save"
              (reify Consumer
                (accept [_ value]
                  (let [^Route route value]
                    (if @held (.resume route)
                        (reset! held [route (.fetch route)]))))))
      (set-yaw! driver 45)
      (save! driver)
      (is (s/wait-until #(do (s/stats driver) (some? @held))))
      (is (saves/same-pose? q45 (durable started id)))
      (is (saves/same-pose? q45 (:part/orientation (sidecar/read-sidecar! (str (:root started)) id))))
      (set-yaw! driver 90)
      (let [[^Route route ^APIResponse response] @held]
        (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))
      (is (s/wait-until #(str/includes? (s/text driver "#bulk-orient-status") "Saved 1 orientation")))
      (is (saves/same-pose? q90 (preview driver id)))
      (is (= 1 (get-in (s/stats driver) [:bulk :dirty])))
      (is (= 1 (s/count-els driver "[data-bulk-part][data-dirty=true]")))
      (is (false? (s/js driver "() => document.querySelector('[data-bulk-save-button]').disabled")))
      (s/js driver "async () => { await htmx.ajax('GET', '/orient/parts', {target:'#bulk-orient-results', swap:'outerHTML'}); }")
      (is (= 1 (get-in (s/stats driver) [:bulk :dirty])))
      (is (= 1 (s/count-els driver "[data-bulk-part][data-dirty=true]")))
      (s/click! driver "[data-bulk-reset]")
      (is (saves/same-pose? q45 (preview driver id)))
      (set-yaw! driver 90)
      (save! driver)
      (is (s/wait-until #(zero? (get-in (s/stats driver) [:bulk :dirty]))))
      (is (saves/same-pose? q90 (durable started id)))
      (let [parts (catalog/browse (catalog/snapshot! (:shipyard.catalog/db (:system started))) {})
            reloaded @(catalog/ingest! parts (str (:root started)))]
        (is (saves/same-pose? q90 (:part/orientation (catalog/part reloaded id)))))
      (s/go! driver (s/base-url (:system started)))
      (s/click! driver ".masthead [data-workspace-mode='orient']")
      (is (s/wait-until #(saves/same-pose? q90 (preview driver id))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest partial-save-keeps-only-the-failed-part-dirty
  (let [started (fixture/start! true) driver (s/make-driver)
        a (:prow fixture/ids) b (:bridge fixture/ids)
        file (sidecar/sidecar-file (str (:root started)) b)
        backup (str file ".backup") q45 (orientation/from-euler-degrees 45 0 0)]
    (try
      (open-grid! driver started [a b])
      (fs/move file backup)
      (spit file "{")
      (set-yaw! driver 45)
      (save! driver)
      (is (s/wait-until #(str/includes? (s/text driver "#bulk-orient-status") "Saved 1. Failed:"))
          (str "save status: " (s/text driver "#bulk-orient-status")))
      (is (= 1 (get-in (s/stats driver) [:bulk :dirty])))
      (is (= 1 (s/count-els driver "[data-bulk-part][data-dirty=true]")))
      (is (saves/same-pose? q45 (durable started a)))
      (is (nil? (durable started b)))
      (is (saves/same-pose? q45 (preview driver b)))
      (fs/delete file)
      (fs/move backup file)
      (save! driver)
      (is (s/wait-until #(zero? (get-in (s/stats driver) [:bulk :dirty]))))
      (is (saves/same-pose? q45 (durable started b)))
      (is (saves/same-pose? q45 (:part/orientation (sidecar/read-sidecar! (str (:root started)) b))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest acknowledgement-from-a-finished-grid-cannot-clear-new-edits
  (let [started (fixture/start! true) driver (s/make-driver) ^Page page (:page driver)
        held (atom nil) id (:prow fixture/ids) q90 (orientation/from-euler-degrees 90 0 0)]
    (try
      (open-grid! driver started [id])
      (.route page "**/orient/save"
              (reify Consumer
                (accept [_ value]
                  (let [^Route route value] (reset! held [route (.fetch route)])))))
      (s/js driver "() => { window.oldSaveDone=false; document.body.addEventListener('htmx:beforeRequest', e => { if(e.detail.pathInfo.requestPath === '/orient/save') e.detail.xhr.addEventListener('loadend', () => window.oldSaveDone=true); }); }")
      (set-yaw! driver 45)
      (save! driver)
      (is (s/wait-until #(do (s/stats driver) (some? @held))))
      (s/click! driver "[data-bulk-back]")
      (s/click! driver "[data-bulk-render-button]")
      (is (s/wait-until #(= 1 (get-in (s/stats driver) [:bulk :count]))))
      (set-yaw! driver 90)
      (let [[^Route route ^APIResponse response] @held]
        (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))
      (is (s/wait-until #(s/js driver "() => window.oldSaveDone")))
      (is (= 1 (get-in (s/stats driver) [:bulk :dirty])))
      (is (= 1 (s/count-els driver "[data-bulk-part][data-dirty=true]")))
      (is (saves/same-pose? q90 (preview driver id)))
      (is (false? (s/js driver "() => document.querySelector('[data-bulk-save-button]').disabled")))
      (finally (s/quit! driver) (fixture/stop! started)))))
