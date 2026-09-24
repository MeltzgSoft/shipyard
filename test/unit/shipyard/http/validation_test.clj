(ns shipyard.http.validation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [reitit.coercion.malli :as malli]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [ring.core.protocols :as protocols]
            [shipyard.http.validation :as validation])
  (:import [java.io ByteArrayOutputStream]))

(defn- handler [response]
  (ring/ring-handler
   (ring/router
    [["/command" {:post {:handler (constantly response)
                         :parameters {:form [:map [:revision int?]]}
                         :responses {200 {:body string?}}}}]]
    {:data {:coercion malli/coercion
            :middleware [validation/wrap-errors
                         coercion/coerce-request-middleware
                         coercion/coerce-response-middleware]}})))

(deftest coercion-errors-are-streamable-and-do-not-echo-payloads
  (doseq [[response form status field] [[{:status 200 :body "ok"} {"revision" "bad" "faces" "private-face-data"} 400 "revision"]
                                        [{:status 200 :body {}} {"revision" "1"} 500 nil]]]
    (let [result ((handler response) {:request-method :post :uri "/command" :form-params form})
          out (ByteArrayOutputStream.)]
      (is (= status (:status result)))
      (is (= "text/html; charset=utf-8" (get-in result [:headers "content-type"])))
      (protocols/write-body-to-stream (:body result) result out)
      (is (= (:body result) (.toString out "UTF-8")))
      (when field (is (str/includes? (:body result) field)))
      (is (not (str/includes? (:body result) "private-face-data")))
      (is (str/includes? (get-in result [:headers "HX-Trigger"]) "shipyard:request-error")))))

(deftest unrelated-handler-errors-still-propagate
  (let [error (ex-info "Unexpected error" {})
        handler (validation/wrap-errors (fn [_] (throw error)))]
    (is (identical? error (try (handler {}) (catch Exception e e))))))
