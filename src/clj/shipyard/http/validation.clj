(ns shipyard.http.validation
  "Renderable errors for the HTML application's coercion boundary."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [reitit.ring.coercion :as coercion]
            [shipyard.http.htmx :as htmx]))

(defn- response [request {:keys [status body]}]
  (let [fields (when (map? (:humanized body))
                 (str/join ", " (sort (map name (keys (:humanized body))))))
        message (cond
                  (= status 400) (str "Invalid request" (when (seq fields) (str " (" fields ")"))
                                      ". Nothing was saved.")
                  :else "The server could not prepare a valid response. Reload the page to check the saved state.")]
    ;; Do not log the submitted body: a stroke can contain thousands of faces.
    (log/warn "HTTP validation failed" (:request-method request) (:uri request)
              "status" status "content-type" (get-in request [:headers "content-type"])
              "fields" fields)
    (htmx/fragment [:p {:role "alert"} message]
                   {:status status :events {:request-error {:message message}}})))

(defn wrap-errors [handler]
  (fn
    ([request]
     (try
       (handler request)
       (catch Exception e
         (coercion/handle-coercion-exception e #(response request %) #(throw %)))))
    ([request respond raise]
     (let [failed #(coercion/handle-coercion-exception % (fn [error] (respond (response request error))) raise)]
       (try
         (handler request respond failed)
         (catch Exception e (failed e)))))))
