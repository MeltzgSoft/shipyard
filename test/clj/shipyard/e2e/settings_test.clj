(ns shipyard.e2e.settings-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.library.index :as index]
            [shipyard.system :as system]))

(deftest settings-directory-reports-failure-and-keeps-the-library-usable
  (s/assert-bundle!)
  (let [started (fixture/start! true)
        driver (s/make-driver)
        config-dir (fs/path (:temp started) "config")
        target (system/library-file config-dir)
        candidate (fs/create-dir (fs/path (:temp started) "other-library"))
        library (get-in started [:system :shipyard.library/index])]
    (try
      (fs/create-dirs target)
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver "#bulk-orient-filters")
      (s/click! driver ".settings__summary")
      (s/fill-and-blur! driver "#settings-root" (str candidate))
      (s/click! driver ".settings__save")
      (s/wait-visible! driver "#settings-message .detail__error")
      (testing "the failed write is visible and the original library stays active"
        (is (str/includes? (s/text driver "#settings-message") "directory; expected a file"))
        (is (= (str (:root started)) (index/root! library)))
        (is (pos? (s/count-els driver "[data-part-row]")))
        (is (fs/directory? target))
        (is (empty? (fs/list-dir target)))
        (is (= #{"library.edn"} (set (map fs/file-name (fs/list-dir (fs/parent target)))))))
      (testing "fixing the filesystem allows the same form to succeed"
        (fs/delete target)
        (s/click! driver ".settings__save")
        (is (s/wait-until #(= (str candidate) (index/root! library))))
        (is (s/wait-until #(str/includes? (s/text driver ".settings__current") (str candidate))))
        (is (= (str candidate)
               (get-in (system/load-config! {:config-dir (str config-dir) :env {}})
                       [:shipyard.library/index :root]))))
      (finally (s/quit! driver) (fixture/stop! started)))))
