(ns shipyard.viewport-loading
  "Presentation-only lifetimes for work affecting the preserved viewport."
  (:require [shipyard.viewport-loading.rules :as rules]))

(defonce ^:private work (js/Set.))

(defn begin!
  "Track one operation until it settles or its consumer becomes stale."
  [current?]
  (let [entry #js {:current current?}]
    (.add work entry)
    #(.delete work entry)))

(defn track! [^js promise current?]
  (let [finish! (begin! current?)]
    (.finally promise finish!)))

(defn- context! []
  (when-let [element (.getElementById js/document "workspace-context")]
    [(.. element -dataset -workspace) (.. element -dataset -activation)]))

(defn install! []
  (let [requests (js/WeakMap.)
        body (.-body js/document)]
    (.addEventListener
     body "htmx:beforeRequest"
     (fn [^js event]
       (let [^js detail (.-detail event) target (.-target detail) xhr (.-xhr detail)]
         (when (and xhr (some-> target (.closest "#detail, #bulk-orient")))
           (let [owner (context!)
                 transition? (some-> (.-elt detail) (.closest "[data-workspace-transition]"))]
             (.set requests xhr (begin! #(or transition? (= owner (context!))))))))))
    (.addEventListener
     body "htmx:afterRequest"
     (fn [^js event]
       (let [^js detail (.-detail event) xhr (.-xhr detail)
             element (.-elt detail)]
         (when-let [finish! (.get requests xhr)]
           (finish!)
           (.delete requests xhr))
         ;; A failed self-poll leaves its fragment in place. It is no longer a
         ;; reliable report of live backend work, and must not strand the overlay.
         (when (and (not (.-successful detail)) element)
           (.removeAttribute element "data-viewport-pending")))))))

(defn sync!
  "Project current work onto the overlay; obsolete work never holds it open."
  [^js canvas workspace preparing?]
  (when-let [overlay (.getElementById js/document "viewport-loading")]
    (.forEach work (fn [^js entry] (when-not ((.-current entry)) (.delete work entry))))
    (let [tab (some-> (.querySelector js/document "[data-detail-tab][aria-selected=true]")
                      (.-dataset) (.-detailTab))
          brush? (rules/brush-workflow? workspace tab
                                        (boolean (.querySelector js/document ".paint-editor"))
                                        (boolean (some-> (.querySelector js/document "[data-mount-face-edit]") .-checked)))
          pending? (or preparing? (pos? (.-size work))
                       (.querySelector js/document "#detail [data-viewport-pending], #bulk-orient [data-viewport-pending]"))
          visible? (and (not brush?) (boolean pending?))]
      (when (not= visible? (not (.-hidden overlay)))
        (set! (.-hidden overlay) (not visible?))
        (.setAttribute canvas "aria-busy" (str visible?))))))
