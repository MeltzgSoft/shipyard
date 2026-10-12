(ns shipyard.e2e.export-settings-test
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
            [shipyard.settings.export-directory :as directory]
            [shipyard.system :as system]))

(deftest export-directory-and-variant-defaults-in-settings
  (s/assert-bundle!)
  (let [started (fixture/start! true fixture/library! fixture/author!
                                (system/read-config! "shipyard/systems/file-picker.edn"))
        sys (:system started) running (atom sys) driver (s/make-driver) db (:shipyard.store/db sys)
        library (:shipyard.library/index sys) before (index/parts! library)
        destination (str (directory/resolved-path! (fs/path (:temp started) "exports")))]
    (try
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver "#bulk-orient-filters")
      (s/open-part! driver "prow")
      (s/await-part driver (:prow fixture/ids))
      (s/click! driver "[data-workspace-mode=settings]")
      (s/wait-visible! driver "#export-settings")
      (s/fill-and-blur! driver "#settings-export-root" destination)
      (s/click! driver "[data-workspace-mode=browse]")
      (s/await-part driver (:prow fixture/ids))
      (s/click! driver "[data-workspace-mode=settings]")
      (s/wait-visible! driver "#export-settings")
      (is (= destination (s/js driver "() => document.querySelector('#settings-export-root').value")))
      (s/click! driver "#cut-defaults button")
      (s/wait-visible! driver "[role=status]:text-is('Saved mount-cut defaults.')")
      (is (= destination (s/js driver "() => document.querySelector('#settings-export-root').value")))
      (testing "cancel and unavailable chooser preserve direct entry"
        (with-redefs [picker/choose! (fn [_ _] nil)]
          (s/click! driver "#export-settings [data-picker-browse]")
          (is (s/wait-until #(not (s/js driver "() => document.querySelector('#export-settings [data-picker-browse]').disabled")))))
        (with-redefs [swing/available? (constantly false)]
          (s/click! driver "#export-settings [data-picker-browse]")
          (is (s/wait-until #(str/includes? (s/text driver "#settings-export-root-picker-message") "No graphical desktop"))))
        (is (= destination (s/js driver "() => document.querySelector('#settings-export-root').value"))))
      (testing "chooser sets the field and explicit save creates the destination"
        (with-redefs [picker/choose! (fn [_ kind] (is (= "directory" kind)) destination)]
          (s/click! driver "#export-settings [data-picker-browse]")
          (is (s/wait-until #(not (s/js driver "() => document.querySelector('#export-settings [data-picker-browse]').disabled"))))))
      (s/click! driver "#export-settings button[type=submit]")
      (s/wait-visible! driver "[role=status]:text-is('Saved export directory.')")
      (is (= destination (settings/export-root! db)))
      (s/fill-and-blur! driver "#settings-export-root" (str (fs/path (:root started) "bad-export")))
      (s/click! driver "#export-settings button[type=submit]")
      (s/wait-visible! driver "[role=alert]")
      (is (str/includes? (s/text driver "[role=alert]") "outside the library"))
      (is (= destination (settings/export-root! db)))
      (is (s/js driver "() => document.querySelector('[aria-label=\"Move unsupported up\"]').disabled"))
      (is (s/js driver "() => document.querySelector('[aria-label=\"Move unsupported-pitted down\"]').disabled"))
      (s/click! driver "[aria-label='Move supported up']")
      (s/wait-visible! driver "[role=status]:text-is('Saved default variant order.')")
      (is (= [:supported :unsupported :unsupported-pitted] (settings/variant-preference! db)))
      (is (s/js driver "() => document.querySelector('[aria-label=\"Move supported up\"]').disabled"))
      (is (= before (index/parts! library)))
      (s/click! driver "[data-workspace-mode=browse]")
      (s/await-part driver (:prow fixture/ids))
      (testing "a restarted application restores export settings"
        (ig/halt! @running)
        (reset! running nil)
        (reset! running
                (system/start!
                 (-> (system/load-config! {:profile :test :config-dir (str (fs/path (:temp started) "config")) :env {}})
                     (assoc-in [:shipyard.store/db :data-home] (str (fs/path (:temp started) "data")))
                     (assoc-in [:shipyard.mesh/cache :cache-home] (str (fs/path (:temp started) "cache")))
                     (assoc-in [:shipyard.http/server :port] 0))))
        (s/go! driver (s/base-url @running))
        (s/click! driver "[data-workspace-mode=settings]")
        (s/wait-visible! driver "#export-settings")
        (is (= destination (s/js driver "() => document.querySelector('#settings-export-root').value")))
        (is (s/js driver "() => document.querySelector('[data-default-variants] li').dataset.variant==='supported'")))
      (finally (s/quit! driver) (fixture/stop! (assoc started :system @running))))))
