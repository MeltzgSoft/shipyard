(ns shipyard.vocabulary.dom
  "Transient dropdown interaction over server-rendered classification choices."
  (:require [shipyard.vocabulary.picker :as picker]))

(defn- input [^js root] (.querySelector root "[data-classification-input]"))
(defn- menu [^js root] (.querySelector root "[role=listbox]"))
(defn- field [^js root]
  (or (.getAttribute (input root) "data-classification-field")
      (some-> (.namedItem (.-elements (.-form (input root))) "field") .-value)))
(defn- classification? [^js root]
  (contains? #{"bundle" "class" "role"} (field root)))

(defn- close! [^js root]
  (set! (.-hidden (menu root)) true)
  (.setAttribute (input root) "aria-expanded" "false")
  (.removeAttribute (input root) "aria-activedescendant")
  (.setAttribute (.querySelector root "[data-classification-toggle]") "aria-expanded" "false"))

(defn- highlight! [^js root index]
  (let [items (array-seq (.querySelectorAll (menu root) "[role=option]"))
        index (mod index (max 1 (count items)))]
    (doseq [[n ^js item] (map-indexed vector items)]
      (.setAttribute item "aria-selected" (str (= n index))))
    (when-let [^js item (nth items index nil)]
      (.setAttribute (input root) "aria-activedescendant" (.-id item))
      (.scrollIntoView item #js {:block "nearest"}))))

(defn- open! [^js root all?]
  (when (classification? root)
    (let [field (field root)
          values (map #(.-value ^js %) (array-seq (.querySelectorAll js/document (str "#part-" field "-values option"))))
          query (if all? "" (.-value (input root)))
          options (picker/options values query)
          list (menu root)]
      (.replaceChildren list)
      (doseq [[index {:keys [value new?]}] (map-indexed vector options)]
        (let [item (.createElement js/document "button")]
          (set! (.-type item) "button")
          (set! (.-id item) (str (.-id list) "-option-" index))
          (set! (.-tabIndex item) -1)
          (.setAttribute item "role" "option")
          (.setAttribute item "data-classification-value" value)
          (set! (.-textContent item) (if new? (str "Add “" value "”") value))
          (.append list item)))
      (when (empty? options)
        (let [empty (.createElement js/document "p")]
          (set! (.-textContent empty) "Type a new value.")
          (.append list empty)))
      (set! (.-hidden list) false)
      (.setAttribute (input root) "aria-expanded" "true")
      (.setAttribute (.querySelector root "[data-classification-toggle]") "aria-expanded" "true")
      (highlight! root 0))))

(defn- choose! [^js root ^js option]
  (set! (.-value (input root)) (.getAttribute option "data-classification-value"))
  (.dispatchEvent (input root) (js/Event. "input" #js {:bubbles true}))
  (.dispatchEvent (input root) (js/Event. "change" #js {:bubbles true}))
  (close! root))

(defn- sync! [^js root]
  ;; Keep one successful value field for classifications, names and import variants.
  ;; The native datalist remains useful until this enhancement initializes.
  (.removeAttribute (input root) "list")
  (let [enabled? (classification? root)]
    (set! (.-hidden (.querySelector root "[data-classification-toggle]")) (not enabled?))
    (when-let [help (.querySelector root "small")] (set! (.-hidden help) (not enabled?)))
    (if enabled? (.setAttribute (input root) "role" "combobox") (.removeAttribute (input root) "role")))
  (close! root))

(defn install! []
  (let [body (.-body js/document)
        roots #(array-seq (.querySelectorAll js/document ".classification-picker"))]
    (doseq [root (roots)] (sync! root))
    (.addEventListener body "htmx:afterSwap"
                       #(doseq [root (roots) :when (.hasAttribute (input root) "list")] (sync! root)))
    (.addEventListener body "change"
                       (fn [^js e]
                         (when (.matches (.-target e) ".part-bulk-edit select[name=field]")
                           (sync! (.querySelector (.-form (.-target e)) ".classification-picker")))))
    (.addEventListener body "input"
                       (fn [^js e]
                         (when (.hasAttribute (.-target e) "data-classification-input")
                           (open! (.closest (.-target e) ".classification-picker") false))))
    (.addEventListener body "pointerdown"
                       (fn [^js e]
                         (when (.closest (.-target e) "[data-classification-value]") (.preventDefault e))))
    (.addEventListener body "click"
                       (fn [^js e]
                         (let [target (.-target e) root (.closest target ".classification-picker")]
                           (doseq [other (roots) :when (not= root other)] (close! other))
                           (cond
                             (.hasAttribute target "data-classification-toggle")
                             (if (.-hidden (menu root)) (do (.focus (input root)) (open! root true)) (close! root))
                             (.hasAttribute target "data-classification-value") (choose! root target)
                             (.hasAttribute target "data-classification-input") (open! root true)))))
    (.addEventListener body "focusout"
                       (fn [^js e]
                         (when-let [root (.closest (.-target e) ".classification-picker")]
                           (when-not (.contains root (.-relatedTarget e)) (close! root)))))
    (.addEventListener body "keydown"
                       (fn [^js e]
                         (when (.hasAttribute (.-target e) "data-classification-input")
                           (let [root (.closest (.-target e) ".classification-picker")
                                 items (array-seq (.querySelectorAll (menu root) "[role=option]"))
                                 selected (.querySelector (menu root) "[aria-selected=true]")
                                 index (.indexOf (clj->js items) selected)]
                             (case (.-key e)
                               ("ArrowDown" "ArrowUp")
                               (when (classification? root)
                                 (.preventDefault e)
                                 (if (.-hidden (menu root)) (open! root false)
                                     (highlight! root (+ index (if (= "ArrowDown" (.-key e)) 1 -1)))))
                               "Enter" (when (and (not (.-hidden (menu root))) selected)
                                         (.preventDefault e) (choose! root selected))
                               "Escape" (when-not (.-hidden (menu root)) (.preventDefault e) (close! root))
                               "Tab" (close! root)
                               nil)))))))
