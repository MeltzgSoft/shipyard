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
        (doseq [[field kind] [["settings-root" "directory"] ["setup-root" "directory"]]]
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
      (testing "stale workspace requests cannot open an import chooser"
        (with-redefs [picker/choose! (fn [& _] (throw (AssertionError. "Must not open a dialog")))]
          (is (= 204 (:status (handler (-> (mock/request :post "/imports/choose")
                                           (mock/header "x-shipyard-workspace" "browse")
                                           (mock/header "x-shipyard-activation" "999"))))))))
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

(deftest headless-detection-and-typed-path-selection
  ;; A fresh JVM checks real headless detection independently of the test runner's
  ;; cached GraphicsEnvironment, without attempting to open a user's desktop.
  (let [code '(do
                (require '[shipyard.file-picker.db :as picker])
                (doseq [kind ["directory" "zip"]]
                  (try
                    (picker/choose! {:lock (java.util.concurrent.locks.ReentrantLock.)} kind)
                    (throw (AssertionError. "A headless dialog must not open"))
                    (catch clojure.lang.ExceptionInfo e
                      (assert (= "No graphical desktop is available to Shipyard. Open Shipyard in a graphical desktop session to use the file selector."
                                 (ex-message e))))))
                ;; Exercise the installed Swing UI's filename field and approval
                ;; action without displaying a window. A full path needs no custom
                ;; accessory or separate text input in the web application.
                (javax.swing.SwingUtilities/invokeAndWait
                 (fn []
                   (let [file (java.io.File/createTempFile "Shipyard typed path " ".zip")]
                     (try
                       (let [chooser (javax.swing.JFileChooser.)
                             ui (.getUI chooser)]
                         (.setFileName ui (.getAbsolutePath file))
                         (.actionPerformed (.getApproveSelectionAction ui) nil)
                         (assert (= file (.getSelectedFile chooser))))
                       (finally (.delete file))))))
                (println "headless-picker-ok")
                (shutdown-agents))
        java (fs/path (System/getProperty "java.home") "bin"
                      (if (str/starts-with? (System/getProperty "os.name") "Windows") "java.exe" "java"))
        script (fs/create-temp-file {:prefix "shipyard headless picker " :suffix ".clj"})]
    (try
      ;; A file avoids platform-specific command-line quoting of embedded strings.
      (spit (str script) (pr-str code))
      (let [{:keys [exit out err]} (shell/sh (str java) "-Djava.awt.headless=true"
                                             "-cp" (System/getProperty "java.class.path")
                                             "clojure.main" (str script))]
        (is (zero? exit) err)
        (is (str/includes? out "headless-picker-ok")))
      (finally (fs/delete-if-exists script)))))
