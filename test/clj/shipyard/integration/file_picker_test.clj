(ns shipyard.integration.file-picker-test
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.file-picker.db :as picker]
            [shipyard.file-picker.swing :as swing]
            [shipyard.library.index :as index])
  (:import [java.util.concurrent.locks ReentrantLock]))

(deftest desktop-selector-http-workflow
  (let [started (fixture/start!) handler (:handler started)
        library (get-in started [:system :shipyard.library/index])
        before (index/root! library)
        request #(handler (mock/request :post (str "/files/choose/" %)))]
    (try
      (testing "each form requests its desktop picker and receives only its own input"
        (doseq [[field kind] [["settings-root" "directory"] ["setup-root" "directory"] ["import-archive" "zip"]]]
          (with-redefs [picker/choose! (fn [_ actual] (is (= kind actual)) "/tmp/Ships & Fleet.zip")]
            (let [response (request field)]
              (is (= 200 (:status response)))
              (is (str/includes? (:body response) (str "id=\"" field "\"")))
              (is (str/includes? (:body response) "Ships &amp; Fleet.zip"))
              (is (str/includes? (:body response) "hx-swap-oob=\"outerHTML\""))))))
      (testing "cancel and unavailable desktop preserve the current library"
        (with-redefs [picker/choose! (fn [_ _] nil)]
          (is (= 204 (:status (request "settings-root")))))
        (with-redefs [picker/choose! (fn [_ _] (throw (ex-info "Desktop unavailable" {})))]
          (is (str/includes? (:body (request "settings-root")) "Desktop unavailable")))
        (is (= before (index/root! library))))
      (testing "unknown fields and GET requests never open a dialog"
        (with-redefs [picker/choose! (fn [& _] (throw (AssertionError. "Must not open a dialog")))]
          (is (= 400 (:status (request "arbitrary-input"))))
          (is (= 405 (:status (handler (mock/request :get "/files/choose/settings-root")))))
          (is (= 404 (:status (handler (mock/request :get "/files")))))))
      (finally (fixture/stop! started)))))

(deftest desktop-dialog-lock-and-runtime
  (let [dialog (ig/init-key :shipyard.file-picker/db {})
        ^ReentrantLock lock (:lock dialog)]
    (testing "desktop backend can be queried without displaying anything"
      (is (boolean? (swing/available?))))
    (testing "a second request cannot queue another desktop dialog"
      (.lock lock)
      (try
        (is (= "A file selector is already open. Finish or cancel it before opening another."
               @(future (try (picker/choose! dialog "zip")
                             (catch clojure.lang.ExceptionInfo e (ex-message e))))))
        (finally (.unlock lock))))
    (testing "cancel and exceptions release the lock"
      (with-redefs [swing/choose! (fn [_] nil)]
        (is (nil? (picker/choose! dialog "directory"))))
      (with-redefs [swing/choose! (fn [_] (throw (ex-info "Desktop unavailable" {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Desktop unavailable" (picker/choose! dialog "zip"))))
      (is (not (.isLocked lock))))))

(deftest headless-picker-allows-manual-entry
  ;; A fresh JVM checks real headless detection independently of the test runner's
  ;; cached GraphicsEnvironment, without attempting to open a user's desktop.
  (let [code '(do
                (require '[shipyard.file-picker.db :as picker])
                (doseq [kind ["directory" "zip"]]
                  (try
                    (picker/choose! {:lock (java.util.concurrent.locks.ReentrantLock.)} kind)
                    (throw (AssertionError. "A headless dialog must not open"))
                    (catch clojure.lang.ExceptionInfo e
                      (assert (= "No graphical desktop is available to Shipyard. You can enter the path directly."
                                 (ex-message e))))))
                (println "headless-picker-ok")
                (shutdown-agents))
        java (fs/path (System/getProperty "java.home") "bin"
                      (if (str/starts-with? (System/getProperty "os.name") "Windows") "java.exe" "java"))
        {:keys [exit out err]} (shell/sh (str java) "-Djava.awt.headless=true"
                                         "-cp" (System/getProperty "java.class.path")
                                         "clojure.main" "-e" (pr-str code))]
    (is (zero? exit) err)
    (is (str/includes? out "headless-picker-ok"))))
