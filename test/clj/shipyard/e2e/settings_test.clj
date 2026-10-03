(ns shipyard.e2e.settings-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.file-picker.db :as picker]
            [shipyard.file-picker.swing :as swing]
            [shipyard.library.index :as index]
            [shipyard.settings.db :as settings]
            [shipyard.system :as system]))

(deftest database-settings-failure-allows-retry-and-restores-selection-after-restart
  (s/assert-bundle!)
  (let [started (fixture/start! true)
        running (atom (:system started))
        driver (s/make-driver)
        config-dir (fs/path (:temp started) "config")
        candidate (fs/create-dir (fs/path (:temp started) "other-library"))
        library (:shipyard.library/index @running)
        database (:shipyard.store/db @running)
        save-root! settings/save-library-root!]
    (try
      (s/go! driver (s/base-url @running))
      (s/wait-visible! driver "#bulk-orient-filters")
      (s/click! driver "[data-workspace-mode=settings]")
      (s/wait-visible! driver "#settings-workspace")
      (s/choose-path! driver "#settings" candidate)
      (is (= (str (:root started)) (index/root! library)) "selection alone does not relocate")
      (testing "cancel leaves the selection unchanged"
        (with-redefs [picker/choose! (fn [_ _] nil)]
          (s/click! driver "#settings [data-picker-browse]")
          (is (s/wait-until #(not (s/js driver "() => document.querySelector('#settings [data-picker-browse]').disabled"))))
          (is (= (str candidate) (s/js driver "() => document.querySelector('#settings-root').value")))))
      (testing "headless Browse gives guidance and preserves the selected path"
        (with-redefs [swing/available? (constantly false)]
          (s/click! driver "#settings [data-picker-browse]")
          (is (s/wait-until #(str/includes? (s/text driver "#settings-root-picker-message")
                                            "No graphical desktop is available")))
          (is (= (str candidate) (s/js driver "() => document.querySelector('#settings-root').value")))))
      (testing "display failures allow direct path entry"
        (with-redefs [swing/choose! (fn [_] (throw (java.awt.AWTError. "Display connection failed")))]
          (s/click! driver "#settings [data-picker-browse]")
          (is (s/wait-until #(str/includes? (s/text driver "#settings-root-picker-message")
                                            "Check that Java has desktop support")))
          (is (= (str candidate) (s/js driver "() => document.querySelector('#settings-root').value")))))
      (s/fill-and-blur! driver "#settings-root" (str candidate))
      (with-redefs [settings/save-library-root!
                    (fn [transaction root]
                      (save-root! transaction root)
                      (throw (ex-info "Database commit failed" {})))]
        (s/click! driver ".settings__save")
        (s/wait-visible! driver "#settings-message .detail__error")
        (testing "the failed transaction keeps the original library usable"
          (is (str/includes? (s/text driver "#settings-message") "Database commit failed"))
          (is (= (str (:root started)) (index/root! library)))
          (is (= (str (:root started)) (settings/library-root! database)))
          (is (pos? (count (index/parts! library))))))
      (testing "the same form succeeds after database writes recover"
        (s/click! driver ".settings__save")
        (is (s/wait-until #(= (str candidate) (index/root! library))))
        (is (s/wait-until #(str/includes? (s/text driver ".settings__current") (str candidate))))
        (is (= (str candidate) (settings/library-root! database))))
      (testing "a fresh server and connection restore the saved root before scanning"
        (ig/halt! @running)
        (reset! running nil)
        (let [cfg (-> (system/load-config! {:profile :test :config-dir (str config-dir) :env {}})
                      (assoc-in [:shipyard.store/db :data-home] (str (fs/path (:temp started) "data")))
                      (assoc-in [:shipyard.mesh/cache :cache-home] (str (fs/path (:temp started) "cache")))
                      (assoc-in [:shipyard.http/server :port] 0))]
          (is (nil? (get-in cfg [:shipyard.library/index :root])))
          (reset! running (system/start! cfg)))
        (s/go! driver (s/base-url @running))
        (s/click! driver "[data-workspace-mode=settings]")
        (s/wait-visible! driver "#settings-workspace")
        (s/wait-visible! driver ".settings__current")
        (is (str/includes? (s/text driver ".settings__current") (str candidate)))
        (is (= (str candidate) (index/root! (:shipyard.library/index @running))))
        (is (empty? (index/parts! (:shipyard.library/index @running)))))
      (testing "settings and index persistence create no EDN cache or settings files"
        (is (not (fs/exists? (fs/path config-dir "shipyard" "library.edn"))))
        (is (not (fs/exists? (fs/path config-dir "shipyard" "config.edn"))))
        (is (empty? (fs/glob (fs/path (:temp started) "cache") "**/index*.edn"))))
      (finally
        (s/quit! driver)
        (fixture/stop! (assoc started :system @running))))))
