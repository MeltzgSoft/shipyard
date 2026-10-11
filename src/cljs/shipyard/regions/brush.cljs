(ns shipyard.regions.brush
  "Part Browser region assignment reuses the visible-only Paint picking pass."
  (:require [cljs.reader :as reader]
            [shipyard.regions.dom :as dom]
            [shipyard.regions.mirror :as mirror]
            [shipyard.regions.transport :as transport]
            [shipyard.paint.brush :as brush]
            [shipyard.paint.render :as render]
            [shipyard.regions.model :as model]
            [shipyard.regions.surface-preparation :as surfaces]))

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

(defn install! [sys apply-material!]
  (transport/install!)
  (let [{:keys [^js canvas ^js controls active activation parts mount-colors-enabled]} sys
        stroke (atom nil)
        projection-locks (atom nil)
        mirror-controls (mirror/install! sys)
        surface-preparation (surfaces/install! sys)
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
            (panel-key [^js panel]
              (when-let [form (.querySelector panel "#region-stroke")]
                (mapv #(.-value (field form %)) ["part-id" "mesh-key" "revision"])))
            (current-panel? [panel]
              (let [[part-id mesh-key] (panel-key panel)
                    ^js object (get @parts part-id)
                    selected @(:current sys)]
                (and @active (identical? panel (.getElementById js/document "part-regions"))
                     (seq part-id) (seq mesh-key)
                     (or (nil? selected) (and (= part-id (:part-id selected)) (= mesh-key (:mesh-key selected))))
                     (or (nil? object) (= mesh-key (.. object -userData -meshKey))))))
            (release-projection! []
              (when-let [{:keys [panel locked]} @projection-locks]
                (doseq [[el disabled] locked :when (.-isConnected el)] (set! (.-disabled el) disabled))
                (.removeAttribute panel "aria-busy")
                (reset! projection-locks nil)
                ((:restore! mirror-controls))))
            (finish-save! [current]
              (unlock! current)
              (reset! stroke nil)
              (status! "Regions saved.")
              (when-let [form (form!)] (set! (.-value (field form "radius")) (:radius current)))
              (select-layer! (:selected-layer current))
              (select-mode! (:mode current)))
            (cancel! [message]
              (when-let [current @stroke]
                (when-not (:awaiting-panel current) (paint! (:object current) (:before current)))
                (unlock! current)
                (select-layer! (:selected-layer current))
                (reset! stroke nil)
                (set! (.. cursor -style -display) "none")
                (if @projection-locks
                  (do (swap! projection-locks assoc :completion-message message)
                      (status! "Preparing saved regions…"))
                  (status! message))))
            (sample! [^js e]
              (when-let [{:keys [buffer radius ^js object before layer previous groups mirror-buffer] :as current} @stroke]
                (when-not (:saving? current)
                  (let [bounds (.getBoundingClientRect canvas)
                        x (- (.-clientX e) (.-left bounds)) y (- (.-clientY e) (.-top bounds))
                        [lx ly] (or previous [x y])
                        steps (max 1 (js/Math.ceil (/ (js/Math.hypot (- x lx) (- y ly)) (max 1 (/ radius 2)))))
                        triangles (reduce into #{} (for [i (range 1 (inc steps))
                                                         picking-buffer (cond-> [buffer] mirror-buffer (conj mirror-buffer))]
                                                     (brush/visible-triangles picking-buffer
                                                                              (+ lx (* (/ i steps) (- x lx)))
                                                                              (+ ly (* (/ i steps) (- y ly))) radius)))
                        triangles (remove (:triangles current) triangles)
                        triangles (if groups (surfaces/expand groups triangles) (set triangles))
                        keys (into (:keys current) (map #(render/face-key (.-geometry object) %)) triangles)
                        changed (when (seq triangles)
                                  (model/change before (:mesh-key before) (:revision before) "assign" layer (vec keys)))]
                    (swap! stroke assoc :keys keys :triangles (into (:triangles current) triangles) :previous [x y])
                    (when-let [regions (:regions changed)]
                      (paint! object (cond-> (merge before (select-keys regions [:faces :revision]))
                                       (= layer "Primary") (update :faces merge (zipmap keys (repeat "Primary"))))))
                    (status! (str (count keys) " faces · release to save"))))))]
      (set! (.-className cursor) "paint-brush-cursor")
      (.appendChild (.-body js/document) cursor)
      (.addEventListener js/document "shipyard:region-projection-pending"
                         (fn [^js event]
                           (let [panel (.-target event)]
                             (when (and (current-panel? panel) (not (identical? panel (:panel @projection-locks))))
                               (release-projection!)
                               (let [locked (mapv (fn [el] [el (.-disabled el)])
                                                  (array-seq (.querySelectorAll panel "button, input:not([type=hidden]), select")))]
                                 (reset! projection-locks {:panel panel :key (panel-key panel) :activation @activation :locked locked})
                                 (.setAttribute panel "aria-busy" "true")
                                 (doseq [[el _] locked] (set! (.-disabled el) true))
                                 (status! "Preparing saved regions…"))))))
      (.addEventListener js/document "shipyard:region-projection"
                         (fn [^js event]
                           (let [panel (.-target event)
                                 pending @projection-locks
                                 current @stroke
                                 valid? (and (identical? panel (:panel pending)) (current-panel? panel)
                                             (= (:activation pending) @activation) (= (:key pending) (panel-key panel)))]
                             (when (identical? panel (:panel pending))
                               (release-projection!)
                               (when valid?
                                 (if (and current (identical? panel (:awaiting-panel current))
                                          (= (:saved-key current) (panel-key panel)))
                                   (finish-save! current)
                                   (when-not current (status! (or (:completion-message pending) "Saved regions ready.")))))
                               (when (and (not valid?) current (identical? panel (:awaiting-panel current)))
                                 (unlock! current)
                                 (reset! stroke nil))))))
      (.addEventListener js/document "htmx:afterSwap"
                         (fn [_]
                           (let [current @stroke
                                 panel (.getElementById js/document "part-regions")]
                             (when (and (:awaiting-panel current) panel (current-panel? panel)
                                        (= (:saved-key current) (panel-key panel)))
                               (swap! stroke assoc :awaiting-panel panel)))
                           (when-let [panel (:panel @projection-locks)]
                             (when-not (identical? panel (.getElementById js/document "part-regions"))
                               (release-projection!)))))
      (.addEventListener js/document "shipyard:workspace"
                         (fn [_]
                           (when-let [panel (.getElementById js/document "part-regions")]
                             (when (current-panel? panel)
                               (if-let [pending @projection-locks]
                                 (when (and (identical? panel (:panel pending)) (= (:key pending) (panel-key panel)))
                                   (swap! projection-locks assoc :activation @activation))
                                 (when (and (= "true" (.getAttribute panel "data-region-projection-ready"))
                                            (.startsWith (or (some-> (.getElementById js/document "region-status") .-textContent) "") "Preparing saved regions"))
                                   (status! "Saved regions ready.")))))))
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
                                                   :mirror-buffer ((:begin! mirror-controls) slot object)
                                                   :groups (when (= "faces" (.-value (field form "mode"))) ((:groups! surface-preparation)))
                                                   :buffer (brush/cached-visible-buffer! picking sys slot) :keys #{} :triangles #{} :locked locked})
                                   (set! (.-enabled controls) false)
                                   (doseq [[el _] locked] (set! (.-disabled el) true))
                                   (.setPointerCapture canvas (.-pointerId e))
                                   (sample! e)))
                               (catch :default error
                                 (let [message (if (#{:mirror-input :surface-preparation} (:type (ex-data error)))
                                                 (ex-message error)
                                                 "Could not start region brush. Reopen this part and retry.")]
                                   (cancel! message)
                                   (status! message)))))) true)
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
                           (when-let [current (when-not (:saving? @stroke) @stroke)]
                             (.preventDefault e) (.stopImmediatePropagation e)
                             (if (empty? (:keys current)) (cancel! "No visible faces selected.")
                                 (let [form (:form current) ^js object (:object current)]
                                   (swap! stroke assoc :saving? true)
                                   ;; The selected layer must be submitted while other controls stay locked.
                                   (set! (.-disabled (field form "layer")) false)
                                   (set! (.-disabled (field form "angle")) false)
                                   (set! (.-value (field form "layer")) (:layer current))
                                   (let [failed! (fn [_]
                                                   (when (identical? form (:form @stroke))
                                                     (cancel! "Region save failed. Reopen this part or retry the stroke.")))]
                                     (try (-> (transport/post! form (render/triangle-count (.-geometry object)) (:triangles current)) (.catch failed!))
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
      (.addEventListener js/document "shipyard:request-error"
                         (fn [^js e]
                           (when (.closest (.-target e) "#part-regions")
                             (let [message (:message (reader/read-string (.. e -detail -value)))]
                               (cancel! message)
                               (status! message)))))
      (doseq [event-name ["htmx:responseError" "htmx:sendError" "htmx:timeout"]]
        (.addEventListener js/document event-name
                           (fn [^js event]
                             (let [panel (:panel @projection-locks)
                                   target (.. event -detail -elt)]
                               (when (and panel target (.contains panel target))
                                 (release-projection!)
                                 (cancel! "Could not restore saved regions. Reopen this part to retry.")
                                 (when-let [form (form!)] (set! (.-disabled (field form "radius")) true))
                                 (status! "Could not restore saved regions. Reopen this part to retry."))))))
      (.addEventListener js/document "htmx:afterRequest"
                         (fn [^js e]
                           (when-let [current @stroke]
                             (when (identical? (:form current) (.. e -detail -requestConfig -elt))
                               (if (and (.. e -detail -successful)
                                        (not (.querySelector js/document "#part-regions [role=alert]")))
                                 (let [panel (.getElementById js/document "part-regions")
                                       ^js object (:object current)
                                       saved (dom/regions! (.. object -userData -partId) (.. object -userData -meshKey))]
                                   (if saved (finish-save! current)
                                       (do (swap! stroke assoc :awaiting-panel panel :saved-key (panel-key panel))
                                           (status! "Preparing saved regions…"))))
                                 (cancel! "Region save failed. Reopen this part or retry the stroke."))))))
      (.addEventListener js/document "htmx:beforeRequest"
                         (fn [^js e]
                           (set! (.. cursor -style -display) "none")
                           (when (and @stroke (not= (:form @stroke) (.. e -detail -elt))
                                      (not (identical? (:awaiting-panel @stroke) (.. e -detail -elt))))
                             (cancel! "Region stroke canceled by navigation.")))))))
