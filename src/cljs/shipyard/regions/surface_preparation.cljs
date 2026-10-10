(ns shipyard.regions.surface-preparation
  "Eager guarded fetches; live expansion reads compact component buffers only."
  (:require [shipyard.preparation :as preparation]))

(defn decode [buffer]
  (let [words (js/Uint32Array. buffer)
        triangles (aget words 0) components (aget words 1)]
    {:ids (.subarray words 2 (+ 2 triangles))
     :offsets (.subarray words (+ 2 triangles) (+ 3 triangles components))
     :members (.subarray words (+ 3 triangles components))}))

(defn expand [{:keys [ids offsets members]} seeds]
  (reduce (fn [result component]
            (loop [i (aget offsets component) result result]
              (if (< i (aget offsets (inc component)))
                (recur (inc i) (conj result (aget members i))) result)))
          #{} (into #{} (map #(aget ids %)) seeds)))

(defn install! [{:keys [active activation parts current]}]
  (let [state (atom nil)
        selection (fn []
                    (when-let [form (.getElementById js/document "region-stroke")]
                      (let [field #(.namedItem (.-elements form) %)
                            {:keys [part-id mesh-key]} @current object (get @parts part-id)]
                        (when (and @active object (seq (.getClientRects form))
                                   (= part-id (.-value (field "part-id")))
                                   (= mesh-key (.-value (field "mesh-key")))
                                   (= "faces" (.-value (field "mode"))))
                          {:key [@activation part-id mesh-key (.-value (field "angle"))]
                           :form form :part-id part-id :mesh-key mesh-key :angle (.-value (field "angle"))}))))]
    (letfn [(valid? [key] (= key (:key (selection))))
            (status! [key status]
              (when (valid? key)
                (.setAttribute (:form (selection)) "data-surface-state" (name status))
                (when-let [element (.getElementById js/document "region-status")]
                  (when (and (= status :ready) (.startsWith (.-textContent element) "Preparing connected surfaces"))
                    (set! (.-textContent element) "Connected surfaces ready.")))))
            (failed! [key]
              (when (valid? key) (swap! state assoc :state :failed) (status! key :failed)))
            (fetch! [{:keys [key part-id mesh-key angle]}]
              (let [query (js/URLSearchParams. #js {:part-id part-id :mesh-key mesh-key :angle angle})]
                (when-let [controller (:controller @state)] (preparation/cancel! controller))
                (swap! state assoc :controller
                       (preparation/load! (str "/parts/regions/surfaces?" query)
                                          {:current? #(valid? key)
                                           :ready! (fn [buffer]
                                                     (swap! state assoc :state :ready :groups (decode buffer))
                                                     (status! key :ready))
                                           :failed! #(failed! key)}))))
            (sync! []
              (if-let [{:keys [key] :as selected} (selection)]
                (do
                  (when (not= key (:key @state))
                    (when-let [controller (:controller @state)] (preparation/cancel! controller))
                    (reset! state {:key key :state :running}) (status! key :running) (fetch! selected))
                  (status! key (:state @state)))
                (when @state
                  (when-let [controller (:controller @state)] (preparation/cancel! controller))
                  (reset! state nil))))]
      (js/setInterval sync! 100)
      (.addEventListener js/document "input" (fn [_] (sync!)))
      (.addEventListener js/document "click" (fn [_] (js/setTimeout sync! 0)))
      {:groups! (fn []
                  (sync!)
                  (if (and (= (:key (selection)) (:key @state)) (= :ready (:state @state)))
                    (:groups @state)
                    (throw (ex-info "Preparing connected surfaces. Try the stroke when preparation is ready."
                                    {:type :surface-preparation}))))})))
