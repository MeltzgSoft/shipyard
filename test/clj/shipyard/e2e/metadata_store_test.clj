(ns shipyard.e2e.metadata-store-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.library.index :as index]))

(deftest malformed-import-keeps-the-working-library
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        other (fs/path (:temp started) "other-library") id (:weapon fixture/ids)
        before (catalog/snapshot! (:shipyard.catalog/db sys))]
    (try
      (fixture/library! other)
      (spit (str (fs/path other id "shipyard.edn")) "{:shipyard/version 99}")
      (s/go! driver (s/base-url sys))
      (s/click! driver ".settings__summary")
      (s/fill-and-blur! driver "#settings-root" (str other))
      (s/click! driver ".settings__save")
      (is (s/wait-until #(str/includes? (s/text driver "#settings-message") "Cannot import")))
      (is (= (str (:root started)) (index/root! (:shipyard.library/index sys))))
      (is (= before (catalog/snapshot! (:shipyard.catalog/db sys))))
      (s/go! driver (s/base-url sys))
      (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
      (s/await-part driver id)
      (is (some #{id} (s/loaded-parts driver)))
      (finally (s/quit! driver) (fixture/stop! started)))))
