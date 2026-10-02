(ns shipyard.desktop.transforms-test
  (:require [cljs.test :refer-macros [deftest is]]
            [shipyard.desktop.backend :as backend]
            [shipyard.desktop.transforms :as t]))

(deftest readiness-is-owned-bounded-and-validated
  (is (= {:port 8082 :url "http://127.0.0.1:8082"}
         (t/readiness "nonce" "SHIPYARD_DESKTOP_READY nonce 8082")))
  (is (nil? (t/readiness "nonce" "SHIPYARD_DESKTOP_READY someone-else 8080")))
  (is (nil? (t/readiness "nonce" "unrelated startup logging")))
  (doseq [bad ["0" "8079" "65536" "8080.5" "8080 extra" "NaN" ""]]
    (is (:error (t/readiness "nonce" (str "SHIPYARD_DESKTOP_READY nonce " bad))))))

(deftest navigation-stays-on-the-owned-http-origin
  (is (t/same-origin? "http://127.0.0.1:8081" "http://127.0.0.1:8081/settings?q=a"))
  (doseq [url ["http://127.0.0.1:8080" "https://127.0.0.1:8081" "http://user@127.0.0.1:8081"
               "file:///etc/passwd" "blob:http://127.0.0.1:8081/test" "javascript:alert(1)" "invalid"]]
    (is (not (t/same-origin? "http://127.0.0.1:8081" url)))))

(deftest external-links-allow-only-ordinary-web-urls
  (is (t/safe-external? "https://example.org/docs"))
  (is (t/safe-external? "http://example.org/"))
  (doseq [url ["mailto:test@example.org" "file:///tmp/test" "javascript:alert(1)"
               "https://user:password@example.org/" "invalid"]]
    (is (not (t/safe-external? url)))))

(deftest packaged-payload-is-explicit-and-never-uses-path-java
  (let [path (js/require "node:path")
        packaged (backend/payload {:packaged? true :resources-path "/bundled" :platform "win32" :java "foreign-java"})
        development (backend/payload {:packaged? false :app-path (.resolve path "checkout" "electron") :resources-path "/ignored" :platform "linux"})]
    (is (= (.join path "/bundled" "runtime" "bin" "java.exe") (:java packaged)))
    (is (= (.join path "/bundled" "shipyard.jar") (:jar packaged)))
    (is (= "java" (:java development)))
    (is (= (.resolve path "checkout" "target" "shipyard-0.1.0-SNAPSHOT.jar") (:jar development)))))
