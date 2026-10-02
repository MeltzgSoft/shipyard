(ns shipyard.integration.desktop-test
  "Actual socket, private pipe and durable-store desktop lifecycle acceptance."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [shipyard.desktop.lifecycle :as desktop]
            [shipyard.desktop.transforms :as transforms]
            [shipyard.http.server :as server]
            [shipyard.settings.db :as settings]
            [shipyard.store.db :as store]
            [shipyard.system :as system])
  (:import [java.io BufferedReader PipedInputStream PipedOutputStream PipedReader PipedWriter]
           [java.net InetAddress ServerSocket URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(defn- listener ^ServerSocket [port]
  (ServerSocket. (int port) 50 (InetAddress/getByName "127.0.0.1")))

(defn- config [directory]
  (-> (system/load-config! {:profile :test :config-dir (str directory) :env {}})
      (assoc-in [:shipyard.store/db :directory] (str (fs/path directory "database")))
      (assoc-in [:shipyard.mesh/cache :cache-home] (str directory))))

(defn- request! ^HttpResponse [port path body]
  (let [builder (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port path)))
                    (.timeout (Duration/ofSeconds 10)))
        request (if body
                  (-> builder (.header "content-type" "application/x-www-form-urlencoded")
                      (.POST (HttpRequest$BodyPublishers/ofString body)) .build)
                  (-> builder .GET .build))]
    (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString))))

(deftest binding-retries-the-actual-server-and-normal-startup-does-not
  (with-open [occupied (listener 0)]
    (let [port (.getLocalPort occupied)
          options {:host "127.0.0.1" :port port :handler (fn [_] {:status 200 :body "owned"})}]
      (testing "normal CLI does not silently change its configured port"
        (is (try (server/start! options) false (catch Exception e (transforms/bind-failure? e)))))
      (testing "bounded exhaustion reports the original bind failure"
        (is (try (server/start! (assoc options :last-port port)) false
                 (catch Exception e (transforms/bind-failure? e)))))
      (testing "desktop retry binds a real next listener"
        (let [started (server/start! (assoc options :last-port 65535))
              actual (server/port! started)]
          (try
            (is (> actual port))
            (is (= "owned" (.body (request! actual "/" nil))))
            (finally (server/stop! started)))
          (with-open [released (listener actual)] (is (= actual (.getLocalPort released)))))))))

(deftest a-non-bind-startup-error-is-not-retried
  ;; Retrying this invalid port would reach zero (an ephemeral successful bind).
  (is (thrown? IllegalArgumentException
               (server/start! {:host "127.0.0.1" :port -1 :last-port 1
                               :handler (fn [_] {:status 200 :body "unexpected"})}))))

(deftest readiness-shutdown-command-eof-and-persistence
  (let [directory (fs/create-temp-dir {:prefix "shipyard-desktop-"})
        library (fs/create-dir (fs/path directory "models"))
        token (str (random-uuid))]
    (try
      (with-open [occupied (listener 8080)]
        (doseq [shutdown [:command :eof]]
          (with-open [input (PipedInputStream.)
                      parent (PipedOutputStream. input)
                      reader (PipedReader.)
                      output (PipedWriter. reader)]
            (let [ready (future (.readLine ^BufferedReader (io/reader reader)))
                  running (future (desktop/run! {:config (config directory) :input input
                                                 :output output :token token}))]
              (try
                (let [line (deref ready 60000 :timeout)]
                  (is (string? line) "readiness must follow successful startup")
                  (when (string? line)
                    (is (.startsWith ^String line (str transforms/ready-prefix " " token " ")))
                    (let [port (parse-long (last (.split ^String line " ")))]
                      (is (> port (.getLocalPort occupied)))
                      (is (= 200 (.statusCode (request! port "/healthz" nil))))
                      (is (= 204 (.statusCode
                                  (request! port "/settings"
                                            (str "root=" (URLEncoder/encode (str library) StandardCharsets/UTF_8))))))
                      (if (= shutdown :command)
                        (do (.write parent (.getBytes "SHIPYARD_DESKTOP_STOP\n" StandardCharsets/UTF_8)) (.flush parent))
                        (.close parent))
                      (is (nil? (deref running 60000 :timeout)) "shutdown waits for the store and workers")
                      (with-open [released (listener port)] (is (= port (.getLocalPort released)))))))
                (finally
                  (.close parent)
                  (deref running 60000 :timeout)
                  (future-cancel ready)))
              (let [database (store/open! (fs/path directory "database"))]
                (try (is (= (str library) (settings/library-root! database)))
                     (finally (store/close! database))))))))
      (finally (fs/delete-tree directory)))))

(deftest failed-startup-closes-the-partially-initialized-store
  (let [directory (fs/create-temp-dir {:prefix "shipyard-desktop-failed-"})]
    (try
      (with-open [occupied (listener 0)]
        (let [cfg (assoc-in (config directory) [:shipyard.http/server :port] (.getLocalPort occupied))
              failure (try (system/start! cfg) nil (catch Exception error error))
              database (get-in (ex-data failure) [:system :shipyard.store/db])]
          (is (transforms/bind-failure? failure))
          (is (some? database) "the database was opened before the listener failed")
          (is (d/closed? (:conn database)) "startup failure must close the owned store")))
      (finally (fs/delete-tree directory)))))
