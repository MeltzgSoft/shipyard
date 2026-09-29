(ns shipyard.e2e.settings-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
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
      (s/click! driver ".settings__summary")
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
          (is (pos? (s/count-els driver "[data-part-row]")))))
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
        (s/click! driver ".settings__summary")
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
