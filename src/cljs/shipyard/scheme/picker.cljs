(ns shipyard.scheme.picker
  "Local spectrum gestures edit the ordinary material form; HTMX owns saves."
  (:require [shipyard.scheme.color :as color]))

(defn- field [^js form] (.namedItem (.-elements form) "base"))
(defn- emit! [element type] (.dispatchEvent element (js/Event. type #js {:bubbles true})))
(defn- clamp [n] (max 0 (min 1 n)))

(defn- show! [^js form [h s v]]
  (let [square (.querySelector form ".color-spectrum")
        cursor (.querySelector square ".color-spectrum__cursor")]
    (.setProperty (.-style square) "--spectrum-hue" (str "hsl(" h ",100%,50%)"))
    (set! (.. square -dataset -saturation) s)
    (set! (.. square -dataset -brightness) v)
    (set! (.-value (.namedItem (.-elements form) "saturation")) s)
    (set! (.-value (.namedItem (.-elements form) "brightness")) v)
    (set! (.. cursor -style -left) (str (* 100 s) "%"))
    (set! (.. cursor -style -top) (str (* 100 (- 1 v)) "%"))
    (.setAttribute square "aria-valuenow" (* 100 s))
    (.setAttribute square "aria-valuetext" (str "Saturation " (int (* 100 s)) "%, brightness " (int (* 100 v)) "%"))
    (set! (.-value (.querySelector form ".color-hue")) h)))

(defn- hsv [form]
  (let [square (.querySelector form ".color-spectrum")]
    [(js/Number (.-value (.querySelector form ".color-hue")))
     (js/Number (.. square -dataset -saturation)) (js/Number (.. square -dataset -brightness))]))

(defn- edit! [form value]
  (show! form value)
  (set! (.-value (field form)) (color/hsv->hex value))
  ;; The HSV gesture is already authoritative locally; a hex round-trip would
  ;; discard hue/saturation at gray/black and quantize near-neutral positions.
  (.dispatchEvent (field form) (js/CustomEvent. "input" #js {:bubbles true :detail #js {:picker true}})))

(defn install! []
  (let [body (.-body js/document) drag (atom nil)
        point! (fn [^js event]
                 (when-let [{:keys [form square]} @drag]
                   (let [rect (.getBoundingClientRect square)]
                     (edit! form [(first (hsv form))
                                  (clamp (/ (- (.-clientX event) (.-left rect)) (.-width rect)))
                                  (- 1 (clamp (/ (- (.-clientY event) (.-top rect)) (.-height rect))))]))))]
    (.addEventListener body "pointerdown"
                       (fn [^js event]
                         (when-let [square (.closest (.-target event) ".color-spectrum")]
                           (let [form (.closest square "form")]
                             (when (and (= 0 (.-button event)) (not (.-disabled (field form))))
                               (.preventDefault event) (.focus square)
                               (.setPointerCapture square (.-pointerId event))
                               (reset! drag {:form form :square square}) (point! event))))))
    (.addEventListener body "pointermove" point!)
    (doseq [type ["pointerup" "pointercancel"]]
      (.addEventListener body type (fn [_]
                                     (when-let [{:keys [form]} @drag]
                                       (reset! drag nil) (emit! (field form) "change")))))
    (.addEventListener body "keydown"
                       (fn [^js event]
                         (when-let [square (.closest (.-target event) ".color-spectrum")]
                           (when-let [[ds dv] ({"ArrowLeft" [-1 0] "ArrowRight" [1 0] "ArrowUp" [0 1] "ArrowDown" [0 -1]} (.-key event))]
                             (let [form (.closest square "form") [h s v] (hsv form) step (if (.-shiftKey event) 0.1 0.01)]
                               (when-not (.-disabled (field form))
                                 (.preventDefault event)
                                 (edit! form [h (clamp (+ s (* ds step))) (clamp (+ v (* dv step)))])
                                 (emit! (field form) "change")))))))
    (.addEventListener body "input"
                       (fn [^js event]
                         (let [target (.-target event) form (.closest target "#scheme-material")]
                           (when form
                             (cond
                               (.matches target ".color-hue")
                               (do (.stopPropagation event) (edit! form (hsv form)))
                               (and (= target (field form)) (not (some-> event .-detail .-picker))
                                    (color/valid-hex? (.-value target)))
                               (let [[h s v] (color/hex->hsv (.-value target))]
                                 (show! form [(if (zero? s) (first (hsv form)) h) s v])))))) true)
    (.addEventListener body "change"
                       (fn [^js event]
                         (when (.matches (.-target event) ".color-hue")
                           (.stopPropagation event)
                           (emit! (field (.closest (.-target event) "form")) "change"))) true)
    (.addEventListener body "click"
                       (fn [^js event]
                         (when-let [button (.closest (.-target event) "[data-color-preset]")]
                           (when-let [form (.getElementById js/document "scheme-material")]
                             (when-not (.-disabled (field form))
                               (edit! form (color/hex->hsv (.getAttribute button "data-color-preset")))
                               (emit! (field form) "change"))))))))
