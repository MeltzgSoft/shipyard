(ns shipyard.preparation
  "Small resource polling with explicit cancellation and consumer activation guards."
  (:require [cljs.reader :as reader]))

(defn cancel! [^js controller] (.abort controller))

(defn load!
  "POST or GET a feature endpoint returning {:state :resource}. Resolve data only
  while current? is true. Returns an AbortController; callbacks never activate stale work.
  :decode! receives Response and defaults to .arrayBuffer; :ready! receives decoded data."
  [url {:keys [method body current? decode! ready! failed! poll-url] :or {method "GET" current? (constantly true)}}]
  (let [controller (js/AbortController.) signal (.-signal controller)
        options #js {:method method :signal signal :headers #js {"content-type" "application/edn"}}
        active? #(and (not (.-aborted signal)) (current?))
        fail! #(when (and (active?) failed!) (failed! %))]
    (when body (set! (.-body options) (pr-str body)))
    (letfn [(fetch! [url options] (-> (js/fetch url options)
                                      (.then (fn [response] (if (.-ok response) response (throw (js/Error. "Preparation unavailable.")))))))
            (poll! [status]
              (when (active?)
                (case (:state status)
                  :ready (-> (fetch! (str "/preparation/" (:resource status) "/data") #js {:signal signal})
                             (.then (or decode! #(.arrayBuffer %)))
                             (.then #(when (active?) (ready! %)))
                             (.catch fail!))
                  :overloaded (js/setTimeout (fn [] (when (active?)
                                                      (-> (fetch! url options)
                                                          (.then #(.text %))
                                                          (.then #(poll! (reader/read-string %)))
                                                          (.catch fail!)))) 100)
                  :running (js/setTimeout (fn [] (when (active?)
                                                   (-> (fetch! (or poll-url (str "/preparation/" (:resource status))) #js {:signal signal})
                                                       (.then #(.text %)) (.then #(poll! (reader/read-string %))) (.catch fail!)))) 50)
                  (fail! (js/Error. (or (:message status) "Preparation unavailable."))))))]
      (-> (fetch! url options) (.then #(.text %)) (.then #(poll! (reader/read-string %))) (.catch fail!)))
    controller))
