(ns shipyard.help.dom
  "Delegated help for server-rendered controls, including HTMX replacements."
  (:require [clojure.string :as str]))

(defonce ^:private installed? (atom false))

(defn install! []
  (when (compare-and-set! installed? false true)
    (let [tooltip (.createElement js/document "div")
          source (volatile! nil)
          previous-description (volatile! nil)
          timer (volatile! nil)
          cancel! (fn [] (js/clearTimeout @timer))
          hide! (fn []
                  (cancel!)
                  (when-let [element @source]
                    (if @previous-description
                      (.setAttribute element "aria-describedby" @previous-description)
                      (.removeAttribute element "aria-describedby")))
                  (vreset! source nil)
                  (set! (.-hidden tooltip) true))
          show! (fn [element]
                  (cancel!)
                  (when (and element (.contains js/document element))
                    (when-not (= element @source)
                      (hide!)
                      (vreset! source element)
                      (vreset! previous-description (.getAttribute element "aria-describedby"))
                      (.setAttribute element "aria-describedby"
                                     (str/join " " (remove str/blank? [@previous-description "control-tooltip"]))))
                    (set! (.-textContent tooltip) (.getAttribute element "data-help"))
                    (set! (.-hidden tooltip) false)
                    (let [rect (.getBoundingClientRect element)
                          width (.-offsetWidth tooltip)
                          height (.-offsetHeight tooltip)
                          left (max 8 (min (.-left rect) (- (.-innerWidth js/window) width 8)))
                          below (+ (.-bottom rect) 8)
                          top (if (<= (+ below height) (- (.-innerHeight js/window) 8))
                                below (max 8 (- (.-top rect) height 8)))]
                      (set! (.. tooltip -style -left) (str left "px"))
                      (set! (.. tooltip -style -top) (str top "px")))))
          trigger (fn [event] (some-> (.-target event) (.closest "[data-help]")))
          leave! (fn [_]
                   (cancel!)
                   (vreset! timer
                            (js/setTimeout
                             (fn []
                               (when-not (or (= @source (.-activeElement js/document))
                                             (.matches tooltip ":hover")
                                             (and @source (.matches @source ":hover")))
                                 (hide!))) 150)))]
      (set! (.-id tooltip) "control-tooltip")
      (set! (.-className tooltip) "control-tooltip")
      (.setAttribute tooltip "role" "tooltip")
      (set! (.-hidden tooltip) true)
      (.appendChild (.-body js/document) tooltip)
      (.addEventListener js/document "pointerover" #(show! (trigger %)))
      (.addEventListener js/document "pointerout" leave!)
      (.addEventListener tooltip "pointerenter" cancel!)
      (.addEventListener tooltip "pointerleave" leave!)
      (.addEventListener js/document "focusin" #(if-let [element (trigger %)] (show! element) (hide!)))
      (.addEventListener js/document "focusout" leave!)
      (.addEventListener js/document "keydown" #(when (= "Escape" (.-key %)) (hide!)))
      (.addEventListener js/document "pointerdown" #(when-not (= tooltip (.-target %)) (hide!)))
      (.addEventListener js/document "click"
                         #(when-let [element (some-> (.-target %) (.closest ".control-help[data-help]"))]
                            (show! element)))
      ;; Focusing an offscreen control scrolls its panel after focusin. Keep
      ;; keyboard help open and reposition it rather than dismissing it.
      (.addEventListener js/document "scroll"
                         #(if (and @source (or (= @source (.-activeElement js/document))
                                               (.matches @source ":hover")))
                            (show! @source) (hide!)) true)
      (.addEventListener js/window "resize" hide!)
      ;; Unrelated thumbnail/progress swaps must not dismiss focused help.
      (doseq [event ["htmx:beforeSwap" "htmx:oobBeforeSwap"]]
        (.addEventListener js/document event
                           #(when (and @source (.. % -detail -target)
                                       (.contains (.. % -detail -target) @source))
                              (hide!))))
      (.addEventListener js/document "htmx:afterSwap"
                         #(when (and @source (not (.contains js/document @source))) (hide!))))))
