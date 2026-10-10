(ns shipyard.preparation.transport
  "Bounded EDN input for small preparation descriptors; dense geometry is never input."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io InputStream]
           [java.nio.charset StandardCharsets]))

(def max-body-bytes 1048576)

(defn wrap-body [handler]
  (fn [request]
    (if (and (= :post (:request-method request))
             (= "application/edn" (some-> (get-in request [:headers "content-type"]) (str/split #";") (first) (str/trim))))
      (let [decoded (try
                      (let [bytes (.readNBytes ^InputStream (:body request) (inc max-body-bytes))]
                        (if (> (alength bytes) max-body-bytes) {:status 413}
                            {:body-params (edn/read-string {:readers {} :default (fn [_ _] (throw (ex-info "Unknown tag" {})))}
                                                           (String. bytes StandardCharsets/UTF_8))}))
                      (catch Exception _ {:status 400}))]
        (if-let [status (:status decoded)]
          {:status status :headers {"content-type" "text/plain"} :body "Invalid preparation request."}
          (handler (merge request decoded))))
      (handler request))))
