(ns shipyard.mount.face-brush
  "Draft-only mount triangle erasing using the shared visible-surface picking pass."
  (:require [cljs.reader :as reader]
            [shipyard.paint.brush :as brush]))

(defn form! [] (.querySelector js/document ".mount-wizard__form"))
(defn enabled? [] (boolean (some-> (form!) (.querySelector "[data-mount-face-edit]") .-checked)))

(defn- indices [^js form]
  (reader/read-string (.-value (.querySelector form "input[name=facet-indices]"))))

(defn- draft! [^js form]
  (or (.-mountFaceDraft form)
      (let [value {:initial (indices form) :history []}]
        (set! (.-mountFaceDraft form) value) value)))

(defn border-indices [] (when-let [form (form!)] (:initial (draft! form))))

(defn install! [{:keys [^js canvas ^js controls active authoring] :as sys} refresh!]
  (let [stroke (atom nil) picking (atom nil) cursor (.createElement js/document "div")
        point (fn [^js e] (let [r (.getBoundingClientRect canvas)] [(- (.-clientX e) (.-left r)) (- (.-clientY e) (.-top r))]))]
    (letfn [(hide! [] (set! (.. cursor -style -display) "none"))
            (status! [^js form]
              (let [selected (indices form)]
                (set! (.-textContent (.querySelector form "[data-mount-face-status]"))
                      (if (seq selected) (str (count selected) " triangles selected · Save commits changes.")
                          "Keep at least one triangle. Undo or reset to restore faces."))
                (doseq [button (array-seq (.querySelectorAll form ".mount-wizard__actions button[type=submit]"))]
                  (set! (.-disabled button) (empty? selected)))
                (set! (.-disabled (.querySelector form "[data-mount-faces-undo]")) (empty? (:history (draft! form))))))
            (write! [^js form selected]
              (set! (.-value (.querySelector form "input[name=facet-indices]")) (pr-str selected))
              (status! form) (refresh! selected))
            (sample! [^js e]
              (when-let [{:keys [form buffer radius previous]} @stroke]
                (let [[x y] (point e) [lx ly] (or previous [x y])
                      steps (max 1 (js/Math.ceil (/ (js/Math.hypot (- x lx) (- y ly)) (max 1 (/ radius 2)))))
                      touched (reduce into #{} (for [i (range 1 (inc steps))]
                                                 (brush/visible-triangles buffer (+ lx (* (/ i steps) (- x lx)))
                                                                          (+ ly (* (/ i steps) (- y ly))) radius)))
                      before (indices form) after (into [] (remove touched) before)]
                  (swap! stroke assoc :previous [x y])
                  (when (not= before after) (write! form after)))))
            (end! [cancel?]
              (when-let [{:keys [^js form before]} @stroke]
                (reset! stroke nil)
                (set! (.-enabled controls) @active)
                (when (.-isConnected form)
                  (if cancel? (write! form before)
                      (when (not= before (indices form))
                        (set! (.-mountFaceDraft form) (update (draft! form) :history #(vec (take-last 30 (conj % before)))))
                        (status! form))))))]
      (set! (.-className cursor) "paint-brush-cursor") (.appendChild (.-body js/document) cursor)
      (.addEventListener canvas "pointerdown"
                         (fn [^js e]
                           (when (and @active @authoring (enabled?) (= 0 (.-button e)) (not (.-altKey e)))
                             (.preventDefault e) (.stopImmediatePropagation e)
                             (let [form (form!) radius (js/Number (.-value (.querySelector form "[data-mount-face-radius]")))
                                   before (indices form) buffer (brush/cached-visible-buffer! picking sys (:part-id @authoring))]
                               (draft! form)
                               (reset! stroke {:form form :before before :buffer buffer :radius radius})
                               (set! (.-enabled controls) false) (.setPointerCapture canvas (.-pointerId e)) (sample! e)))) true)
      (.addEventListener canvas "pointermove"
                         (fn [^js e]
                           (when @stroke (sample! e))
                           (if (and @active @authoring (enabled?) (not (.-altKey e)))
                             (let [radius (.-value (.querySelector (form!) "[data-mount-face-radius]"))]
                               (set! (.. cursor -style -display) "block")
                               (set! (.. cursor -style -left) (str (.-clientX e) "px"))
                               (set! (.. cursor -style -top) (str (.-clientY e) "px"))
                               (set! (.. cursor -style -width) (str (* 2 radius) "px"))
                               (set! (.. cursor -style -height) (str (* 2 radius) "px"))) (hide!))))
      (.addEventListener canvas "pointerup" (fn [_] (end! false)))
      (.addEventListener canvas "pointercancel" (fn [_] (end! true)))
      (.addEventListener canvas "pointerleave" (fn [_] (hide!)))
      (.addEventListener js/document "htmx:beforeRequest" (fn [_] (end! true) (hide!)))
      (.addEventListener js/document "click"
                         (fn [^js e]
                           (let [target (.-target e) form (form!)]
                             (when (and form (.closest target "[data-mount-faces-undo], [data-mount-faces-reset]"))
                               (let [{:keys [initial history] :as draft} (draft! form)]
                                 (if (.closest target "[data-mount-faces-reset]")
                                   (do (set! (.-mountFaceDraft form) (assoc draft :history [])) (write! form initial))
                                   (when-let [before (peek history)]
                                     (set! (.-mountFaceDraft form) (assoc draft :history (pop history))) (write! form before))))))))
      (.addEventListener js/document "change"
                         (fn [^js e]
                           (when (some-> (.-target e) (.closest "[data-mount-face-edit]"))
                             (doseq [el (array-seq (.querySelectorAll (form!) "[data-mount-face-controls]"))]
                               (set! (.-hidden el) (not (enabled?))))
                             (draft! (form!)) (refresh! (indices (form!))) (hide!)))))))
