(ns shipyard.regions.mirror
  "Transient, part-scoped mirror controls and cached geometric correspondence."
  (:require [shipyard.math :as math]
            [shipyard.paint.render :as render]
            [shipyard.regions.symmetry :as symmetry]))

(defn- field [^js form name] (.namedItem (.-elements form) name))

(defn- index! [^js object quaternion]
  (let [cached (.. object -userData -regionMirror)]
    (if (and cached (= quaternion (:orientation cached))) cached
        (let [geometry (.-geometry object)
              value {:orientation quaternion
                     :index (symmetry/index (mapv #(render/triangle-points geometry %) (range (render/triangle-count geometry))) quaternion)
                     :matches (atom {})}]
          (set! (.. object -userData -regionMirror) value)
          value))))

(defn install! [{:keys [current]}]
  (let [settings (atom nil)
        form! #(.getElementById js/document "region-stroke")
        ensure! (fn [form]
                  (let [key [(.-value (field form "part-id")) (.-value (field form "mesh-key")) (:orientation @current)]]
                    (when (not= key (:key @settings))
                      (reset! settings {:key key :enabled false :axis :x :offset ""}))))
        restore! (fn []
                   (when-let [form (form!)]
                     (ensure! form)
                     (let [{:keys [enabled axis offset]} @settings]
                       (set! (.-checked (field form "mirror")) enabled)
                       (set! (.-value (field form "mirror-axis")) (name axis))
                       (set! (.-value (field form "mirror-offset")) offset)
                       (set! (.-disabled (field form "mirror-axis")) (not enabled))
                       (set! (.-disabled (field form "mirror-offset")) (not enabled)))))]
    (.addEventListener js/document "htmx:afterSwap" (fn [_] (restore!)))
    (.addEventListener js/document "shipyard:part-orientation" (fn [_] (restore!)))
    (.addEventListener js/document "change"
                       (fn [^js event]
                         (when-let [form (form!)]
                           (when (and (.contains form (.-target event))
                                      (#{"mirror" "mirror-axis" "mirror-offset"} (.. event -target -name)))
                             (ensure! form)
                             (swap! settings assoc :enabled (.-checked (field form "mirror"))
                                    :axis (keyword (.-value (field form "mirror-axis")))
                                    :offset (if (= "mirror-axis" (.. event -target -name)) ""
                                                (.-value (field form "mirror-offset"))))
                             (restore!)))))
    {:restore! restore!
     :begin! (fn [^js object]
               (when-let [form (form!)]
                 (when (ensure! form) (restore!))
                 (let [{:keys [enabled axis offset]} @settings]
                   (when enabled
                     (let [offset (when (seq offset) (math/parse-finite-double offset))]
                       (when (or (.. (field form "mirror-offset") -validity -badInput)
                                 (and (seq (:offset @settings)) (nil? offset)))
                         (throw (ex-info "Enter a finite mirror plane offset." {:type :mirror-input})))
                       (let [{:keys [index matches]} (index! object (:orientation @current))
                             plane [axis offset]]
                         ;; Keep only the active plane's query cache.
                         (when (not= plane (:plane @matches)) (reset! matches {:plane plane :faces {}}))
                         (fn [triangles]
                           (reduce (fn [selected triangle]
                                     (let [mirrored (or (get-in @matches [:faces triangle])
                                                        (let [value (symmetry/counterparts index axis offset triangle)]
                                                          (swap! matches assoc-in [:faces triangle] value)
                                                          value))]
                                       (into selected mirrored)))
                                   (set triangles) triangles))))))))}))
