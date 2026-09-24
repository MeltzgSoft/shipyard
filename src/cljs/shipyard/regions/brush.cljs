(ns shipyard.regions.brush
  "Part Browser region assignment reuses the visible-only Paint picking pass."
  (:require [shipyard.regions.dom :as dom]
            [shipyard.http.forms :as forms]
            [shipyard.paint.brush :as brush]
            [shipyard.paint.render :as render]
            [shipyard.regions.model :as model]
            [shipyard.regions.surfaces :as surfaces]))

(defn- select-layer! [layer]
  (when-let [form (.getElementById js/document "region-stroke")]
    (set! (.-value (.namedItem (.-elements form) "layer")) layer)
    (doseq [button (array-seq (.querySelectorAll js/document "[data-region-layer]"))]
      (.setAttribute button "aria-pressed" (str (= layer (.getAttribute button "data-region-layer")))))))

(defn- select-mode! [mode]
  (when-let [form (.getElementById js/document "region-stroke")]
    (set! (.-value (.namedItem (.-elements form) "mode")) mode)
    (set! (.-hidden (.getElementById js/document "region-angle-control")) (not= mode "faces"))
    (doseq [button (array-seq (.querySelectorAll js/document "[data-region-mode]"))]
      (.setAttribute button "aria-pressed" (str (= mode (.getAttribute button "data-region-mode")))))))

(defn- surface-groups! [^js object angle]
  (let [cached (.. object -userData -regionSurfaces)]
    (if (= angle (:angle cached)) (:groups cached)
        (let [geometry (.-geometry object)
              groups (surfaces/groups (mapv #(render/triangle-points geometry %) (range (render/triangle-count geometry))) angle)]
          (set! (.. object -userData -regionSurfaces) {:angle angle :groups groups})
          groups))))

(defn install! [sys apply-material!]
  (let [{:keys [^js canvas ^js controls active parts mount-colors-enabled]} sys
        stroke (atom nil)
        picking (atom nil)
        cursor (.createElement js/document "div")
        form! #(.getElementById js/document "region-stroke")
        field (fn [^js form name] (.namedItem (.-elements form) name))
        status! (fn [message] (when-let [el (.getElementById js/document "region-status")] (set! (.-textContent el) message)))
        available? (fn [] (when-let [form (form!)]
                            (and @active (seq (.getClientRects form))
                                 (not (.-disabled (field form "radius"))) (not @stroke))))]
    (letfn [(paint! [object regions]
              (let [palette (model/preview-materials regions)]
                (render/set-regions! object regions palette)
                (apply-material! object (get palette "Primary") @mount-colors-enabled)))
            (unlock! [current]
              (doseq [[el disabled] (:locked current) :when (.-isConnected el)] (set! (.-disabled el) disabled))
              (set! (.-enabled controls) @active))
            (cancel! [message]
              (when-let [current @stroke]
                (paint! (:object current) (:before current))
                (unlock! current)
                (select-layer! (:selected-layer current))
                (reset! stroke nil)
                (set! (.. cursor -style -display) "none")
                (status! message)))
            (sample! [^js e]
              (when-let [{:keys [buffer radius slot ^js object before layer previous groups] :as current} @stroke]
                (when-not (:saving? current)
                  (let [bounds (.getBoundingClientRect canvas)
                        x (- (.-clientX e) (.-left bounds)) y (- (.-clientY e) (.-top bounds))
                        [lx ly] (or previous [x y])
                        steps (max 1 (js/Math.ceil (/ (js/Math.hypot (- x lx) (- y ly)) (max 1 (/ radius 2)))))
                        triangles (reduce into #{} (for [i (range 1 (inc steps))]
                                                     (get (brush/sampled-instances buffer (+ lx (* (/ i steps) (- x lx)))
                                                                                   (+ ly (* (/ i steps) (- y ly))) radius slot false) slot)))
                        triangles (remove (:triangles current) triangles)
                        triangles (if groups (surfaces/expand groups triangles) (set triangles))
                        keys (into (:keys current) (map #(render/face-key (.-geometry object) %)) triangles)
                        changed (when (seq triangles)
                                  (model/change before (:mesh-key before) (:revision before) "assign" layer nil (vec keys)))]
                    (swap! stroke assoc :keys keys :triangles (into (:triangles current) triangles) :previous [x y])
                    (when-let [regions (:regions changed)] (paint! object regions))
                    (status! (str (count keys) " faces · release to save"))))))]
      (set! (.-className cursor) "paint-brush-cursor")
      (.appendChild (.-body js/document) cursor)
      (.addEventListener canvas "pointerdown"
                         (fn [^js e]
                           (when (and (available?) (#{0 2} (.-button e)) (not (.-altKey e)))
                             (.preventDefault e) (.stopImmediatePropagation e)
                             (try
                               (let [form (form!) [slot ^js object] (first @parts)
                                     before (dom/regions! (.. object -userData -partId) (.. object -userData -meshKey))
                                     locked (mapv (fn [el] [el (.-disabled el)])
                                                  (array-seq (.querySelectorAll js/document "#workspace-navigation button, #library button, #library select, #part-regions button, #part-regions input:not([type=hidden]), #part-regions select, [data-detail-tab], [data-mount-colors-toggle]")))]
                                 (when (= (:mesh-key before) (.. object -userData -meshKey))
                                   (reset! stroke {:form form :slot slot :object object :before before
                                                   :selected-layer (.-value (field form "layer"))
                                                   :layer (if (= 2 (.-button e)) "Primary" (.-value (field form "layer")))
                                                   :radius (js/Number (.-value (field form "radius")))
                                                   :mode (.-value (field form "mode"))
                                                   :angle (.-value (field form "angle"))
                                                   :groups (when (= "faces" (.-value (field form "mode"))) (surface-groups! object (js/Number (.-value (field form "angle")))))
                                                   :buffer (brush/cached-visible-buffer! picking sys slot) :keys #{} :triangles #{} :locked locked})
                                   (set! (.-enabled controls) false)
                                   (doseq [[el _] locked] (set! (.-disabled el) true))
                                   (.setPointerCapture canvas (.-pointerId e))
                                   (sample! e)))
                               (catch :default _ (cancel! "Could not start region brush. Reopen this part and retry."))))) true)
      (.addEventListener canvas "pointermove"
                         (fn [^js e]
                           (let [show? (or (available?) (and @stroke (not (:saving? @stroke))))]
                             (set! (.. cursor -style -display) (if show? "block" "none"))
                             (when show?
                               (let [radius (or (:radius @stroke) (js/Number (.-value (field (form!) "radius"))))]
                                 (set! (.. cursor -style -width) (str (* 2 radius) "px"))
                                 (set! (.. cursor -style -height) (str (* 2 radius) "px"))
                                 (set! (.. cursor -style -left) (str (.-clientX e) "px"))
                                 (set! (.. cursor -style -top) (str (.-clientY e) "px")))))
                           (when @stroke (sample! e))) true)
      (.addEventListener canvas "pointerleave" (fn [_] (set! (.. cursor -style -display) "none")))
      (.addEventListener canvas "wheel" (fn [^js e] (when @stroke (.preventDefault e) (.stopImmediatePropagation e))) #js {:capture true :passive false})
      (.addEventListener canvas "pointerup"
                         (fn [^js e]
                           (when-let [current @stroke]
                             (.preventDefault e) (.stopImmediatePropagation e)
                             (if (empty? (:keys current)) (cancel! "No visible faces selected.")
                                 (let [form (:form current)]
                                   (swap! stroke assoc :saving? true)
                                   ;; The selected layer must be submitted while other controls stay locked.
                                   (set! (.-disabled (field form "layer")) false)
                                   (set! (.-disabled (field form "angle")) false)
                                   (set! (.-value (field form "layer")) (:layer current))
                                   (set! (.-value (field form "faces")) (pr-str (vec (:keys current))))
                                   (let [failed! (fn [_]
                                                   (when (identical? form (:form @stroke))
                                                     (cancel! "Region save failed. Reopen this part or retry the stroke.")))]
                                     (try (-> (forms/post! form) (.catch failed!))
                                          (catch :default error (failed! error)))))))) true)
      (.addEventListener canvas "contextmenu" (fn [^js e] (when (or @stroke (available?)) (.preventDefault e) (.stopImmediatePropagation e))) true)
      (.addEventListener canvas "pointercancel" (fn [_] (cancel! "Region stroke canceled.")) true)
      (.addEventListener canvas "click" (fn [^js e] (when (or @stroke (available?)) (.preventDefault e) (.stopImmediatePropagation e))) true)
      (.addEventListener js/document "click"
                         (fn [^js e]
                           (when (and @active (not @stroke))
                             (when-let [button (some-> (.-target e) (.closest "[data-region-layer]"))]
                               (when-not (.-disabled button)
                                 (select-layer! (.getAttribute button "data-region-layer"))))
                             (when-let [button (some-> (.-target e) (.closest "[data-region-mode]"))]
                               (when-not (.-disabled button)
                                 (select-mode! (.getAttribute button "data-region-mode")))))))
      (.addEventListener js/document "shipyard:inspector-tab"
                         (fn [_] (set! (.. cursor -style -display) "none")))
      (.addEventListener js/document "htmx:afterRequest"
                         (fn [^js e]
                           (when-let [current @stroke]
                             (when (identical? (:form current) (.. e -detail -requestConfig -elt))
                               (if (and (.. e -detail -successful)
                                        (not (.querySelector js/document "#part-regions [role=alert]")))
                                 (do (unlock! current) (reset! stroke nil) (status! "Regions saved.")
                                     (when-let [form (form!)]
                                       (set! (.-value (field form "radius")) (:radius current)))
                                     (select-layer! (:selected-layer current))
                                     (select-mode! (:mode current)))
                                 (cancel! "Region save failed. Reopen this part or retry the stroke."))))))
      (.addEventListener js/document "htmx:beforeRequest"
                         (fn [^js e]
                           (set! (.. cursor -style -display) "none")
                           (when (and @stroke (not= (:form @stroke) (.. e -detail -elt)))
                             (cancel! "Region stroke canceled by navigation.")))))))
