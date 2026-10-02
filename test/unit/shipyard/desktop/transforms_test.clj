(ns shipyard.desktop.transforms-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.desktop.transforms :as t])
  (:import [java.net BindException]))

(def token "8a94d0a9-cf61-466c-b6d9-4e243029402a")

(deftest desktop-mode-is-explicit
  (is (t/desktop-mode? ["--desktop"]))
  (is (not (t/desktop-mode? [])))
  (is (not (t/desktop-mode? ["desktop"]))))

(deftest desktop-binding-overrides-only-the-listener
  (let [config {:other {:value 1} :shipyard.http/server {:host "0.0.0.0" :port 9000 :handler :handler}}]
    (is (= {:other {:value 1} :shipyard.http/server
            {:host "127.0.0.1" :port 8080 :last-port 65535 :handler :handler}}
           (t/desktop-config config)))))

(deftest readiness-carries-the-launch-token-and-actual-port
  (is (t/valid-token? token))
  (doseq [bad [nil "" "not-a-token" (str token "\n")]] (is (not (t/valid-token? bad))))
  (is (= (str "SHIPYARD_DESKTOP_READY " token " 8082") (t/ready-line token 8082)))
  (doseq [port [0 8079 65536 8080.5]]
    (is (thrown? clojure.lang.ExceptionInfo (t/ready-line token port)))))

(deftest private-shutdown-accepts-command-and-eof
  (is (t/stop-command? "SHIPYARD_DESKTOP_STOP"))
  (is (t/stop-command? nil))
  (is (not (t/stop-command? "quit"))))

(deftest only-a-real-bind-exception-allows-another-port
  (is (t/bind-failure? (BindException. "busy")))
  (is (t/bind-failure? (ex-info "wrapped" {} (BindException. "busy"))))
  (is (not (t/bind-failure? (Exception. "Address already in use"))))
  (is (not (t/bind-failure? nil))))
