(ns shipyard.regions.mirror
  "Transient, part-scoped mirror controls and reflected brush picking."
  (:require [shipyard.math :as math]
            [shipyard.paint.brush :as brush]
            [cljs.reader :as reader]
            [shipyard.part.orientation :as orientation]
            [shipyard.preparation :as preparation]
            [shipyard.regions.symmetry :as symmetry]))

(defn- field [^js form name] (.namedItem (.-elements form) name))

(defn bounds! [^js object part-orientation]
  (let [prepared (.. object -userData -regionMirrorPrepared)
        [lo hi] (.. object -userData -regionMirrorSourceBounds)]
    (if (= part-orientation (:orientation prepared)) (:bounds prepared)
        (orientation/oriented-bounds (or lo [0 0 0]) (or hi [0 0 0]) part-orientation))))

(defn offset!
  "Explicit offsets stay immediate; automatic offsets come only from source-bound workers."
  [^js object part-orientation axis explicit]
  (if (some? explicit) explicit
      (let [prepared (.. object -userData -regionMirrorPrepared)]
        (when (and (= part-orientation (:orientation prepared)) (= axis (:axis prepared)))
          (:offset prepared)))))

(defn install! [{:keys [current] :as sys}]
  (let [request (atom nil)
        settings (atom nil)
        picking (atom nil)
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
    (letfn [(sync! []
              (when-let [form (form!)]
                (ensure! form)
                (let [{:keys [part-id mesh-key orientation bounds]} @current
                      ^js object (get @(:parts sys) part-id)
                      axis (keyword (.-value (field form "mirror-axis")))
                      key [@(:activation sys) part-id mesh-key orientation axis]
                      current? #(and @(:active sys) (= key [@(:activation sys) (:part-id @current) (:mesh-key @current)
                                                            (:orientation @current)
                                                            (some-> (form!) (field "mirror-axis") (.-value) (keyword))])
                                     (when-let [form (form!)] (.-checked (field form "mirror"))))]
                  (when object (set! (.. object -userData -regionMirrorSourceBounds) bounds))
                  (when-not (current?)
                    (when-let [controller (:controller @request)] (preparation/cancel! controller))
                    (reset! request nil))
                  (when (and object (seq (.getClientRects form)) (current?) (not= key (:key @request)))
                    (when-let [controller (:controller @request)] (preparation/cancel! controller))
                    (reset! request {:key key :state :running})
                    (.setAttribute form "data-mirror-state" "running")
                    (let [query (js/URLSearchParams. #js {:part-id part-id :mesh-key mesh-key
                                                          :axis (name axis) :quaternion (pr-str orientation)})
                          controller (preparation/load!
                                      (str "/parts/regions/mirror?" query)
                                      {:current? current? :decode! #(-> (.text %) (.then reader/read-string))
                                       :ready! (fn [value]
                                                 (swap! request assoc :state :ready)
                                                 (set! (.. object -userData -regionMirrorPrepared)
                                                       (assoc value :orientation orientation :axis axis))
                                                 (when-let [form (form!)] (.setAttribute form "data-mirror-state" "ready")))
                                       :failed! (fn [_] (swap! request assoc :state :failed) (when-let [form (form!)] (.setAttribute form "data-mirror-state" "failed")))})]
                      (swap! request assoc :controller controller)))
                  (when (and (= key (:key @request)) (:state @request))
                    (.setAttribute form "data-mirror-state" (name (:state @request)))))))]
      (js/setInterval sync! 100)
      (.addEventListener js/document "change" (fn [_] (js/setTimeout sync! 0)))
      (.addEventListener js/document "input" (fn [_] (sync!))))
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
     :begin! (fn [slot ^js object]
               (when-let [form (form!)]
                 (when (ensure! form) (restore!))
                 (let [{:keys [enabled axis]} @settings
                       offset (.-value (field form "mirror-offset"))]
                   ;; Pointerdown precedes blur/change on a focused number input.
                   (swap! settings assoc :offset offset)
                   (when enabled
                     (let [offset (when (seq offset) (math/parse-finite-double offset))]
                       (when (or (.. (field form "mirror-offset") -validity -badInput)
                                 (and (seq (:offset @settings)) (nil? offset)))
                         (throw (ex-info "Enter a finite mirror plane offset." {:type :mirror-input})))
                       (let [orientation (:orientation @current)
                             plane (offset! object orientation axis offset)
                             _ (when (nil? plane)
                                 (throw (ex-info "Preparing the automatic mirror plane. Try the stroke when preparation is ready."
                                                 {:type :mirror-input})))
                             transform (symmetry/reflection-matrix (bounds! object orientation) axis plane)]
                         (brush/cached-visible-buffer! picking (assoc sys :picking-transform transform) slot)))))))}))
