(ns shipyard.workspace
  "Workspace navigation and HTMX request ownership, independent of WebGL.")

(defn- query [selector] (.querySelector js/document selector))
(defn- emit! [event detail]
  (.dispatchEvent (.-body js/document) (js/CustomEvent. event #js {:detail (clj->js detail)})))

(defn- current [state]
  (let [{:keys [mode activation workspaces]} @state]
    {:mode mode :activation activation :colors (get-in workspaces [mode :colors] true)}))

(defn- show-colors! [enabled?]
  (.setAttribute (.-body js/document) "data-mount-colors" (str enabled?))
  (when-let [button (query "[data-mount-colors-toggle]")]
    (.setAttribute button "aria-pressed" (str enabled?))))

(defn- activate! [state destination activation colors]
  (swap! state assoc :mode destination :activation activation)
  (when (some? colors) (swap! state assoc-in [:workspaces destination :colors] colors))
  (.setAttribute (.-body js/document) "data-workspace" destination)
  (doseq [link (array-seq (.querySelectorAll js/document ".masthead__mode"))]
    (let [selected? (= destination (.getAttribute link "data-workspace-mode"))]
      (.toggle (.-classList link) "masthead__mode--active" selected?)
      (.setAttribute link "aria-current" (if selected? "page" "false"))))
  (show-colors! (:colors (current state)))
  (emit! "shipyard:workspace" (current state)))

(defn- filter-values []
  (if-let [form (query "#filters, #bulk-orient-filters, .assembly__filters, #ship-filters")]
    (js/Object.fromEntries (js/FormData. form))
    #js {}))

(defn- navigate! [state ^js event]
  (when-let [link (some-> (.-target event) (.closest "[data-workspace-mode]"))]
    (.preventDefault event)
    (.stopImmediatePropagation event)
    (let [{:keys [mode activation colors]} (current state)
          destination (.getAttribute link "data-workspace-mode")
          params (js/URLSearchParams. #js {:from mode :filters (js/JSON.stringify (filter-values)) :colors (str colors)})]
      (when-let [input (query ".assembly__save input[name=name]")]
        (.set params "draft-name" (.-value input)))
      (when (= mode "orient")
        (.set params "selection" (or (some-> (query "[data-bulk-ids]") .-value) "[]"))
        (.set params "grid" (str (boolean (query "[data-bulk-grid]")))))
      (when-let [hull (.getAttribute link "data-hull")] (.set params "part-id" hull))
      (activate! state destination (inc activation) nil)
      (doseq [selector ["#detail" "#library" "#bulk-orient"]]
        (.replaceChildren (query selector)))
      ;; Initialize incoming controls in the insertion turn: the default settle
      ;; delay leaves visible filters briefly unable to handle their first change.
      (.ajax js/htmx "GET" (str "/workspace/" destination "?" params)
             #js {:target "#detail" :swap "innerHTML settle:0ms"}))))

(defn- display! [state ^js event]
  (when (some-> (.-target event) (.closest "[data-mount-colors-toggle]"))
    (.preventDefault event)
    (.stopImmediatePropagation event)
    (let [{:keys [mode colors]} (current state)]
      (swap! state assoc-in [:workspaces mode :colors] (not colors))
      (show-colors! (not colors))
      (emit! "shipyard:display" {:colors (not colors)}))))

(defn- receive! [state ^js event]
  (let [^js xhr (.. event -detail -xhr)
        owner (.-shipyardOwner xhr)
        {:keys [mode activation]} (current state)]
    (if (and owner (or (not= mode (.-mode owner)) (not= activation (.-activation owner))))
      (.preventDefault event)
      (let [destination (.getResponseHeader xhr "X-Shipyard-Destination")
            colors (.getResponseHeader xhr "X-Shipyard-Colors")
            enabled? (when (some? colors) (= "true" colors))]
        (cond
          destination (do (activate! state destination (inc activation) enabled?)
                          (.replaceChildren (query "#bulk-orient")))
          (some? colors) (do (swap! state assoc-in [:workspaces mode :colors] enabled?)
                             (show-colors! enabled?)
                             (emit! "shipyard:display" {:colors enabled?})))))))

(defn- preserve-drawers! [^js event]
  (let [^js target (.. event -detail -target)
        ^js fragment (.. event -detail -fragment)]
    (when (= "library" (.-id target))
      (let [previous (.querySelector target ".assembly__rail-slots")
            incoming (.querySelector fragment ".assembly__rail-slots")]
        (when (and previous incoming (= (.getAttribute previous "data-hull-id") (.getAttribute incoming "data-hull-id")))
          (let [states (into {} (map (fn [drawer] [(.getAttribute drawer "data-slot")
                                                   {:open (.-open drawer) :complete (.getAttribute drawer "data-complete")}]))
                             (array-seq (.querySelectorAll previous "details[data-slot]")))]
            (doseq [drawer (array-seq (.querySelectorAll incoming "details[data-slot]"))]
              (when-let [state (get states (.getAttribute drawer "data-slot"))]
                (set! (.-open drawer) (if (= (:complete state) (.getAttribute drawer "data-complete"))
                                        (:open state) (not= "true" (.getAttribute drawer "data-complete"))))))))))))

(defn ^:export init []
  (let [state (atom {:mode "browse" :activation (.now js/Date) :workspaces {}})]
    (.addEventListener js/document "click" #(navigate! state %) true)
    (.addEventListener js/document "click" #(display! state %) true)
    (.addEventListener js/document "htmx:configRequest"
                       (fn [^js event]
                         (let [{:keys [mode activation colors]} (current state)
                               headers (.. event -detail -headers)]
                           (aset headers "X-Shipyard-Workspace" mode)
                           (aset headers "X-Shipyard-Activation" (str activation))
                           (aset headers "X-Shipyard-Colors" (str colors)))))
    (.addEventListener js/document "htmx:beforeRequest"
                       (fn [^js event]
                         (let [xhr (.. event -detail -xhr)
                               element (.. event -detail -elt)
                               receive (fn receive [^js response]
                                         (when (= xhr (.. response -detail -xhr))
                                           (.removeEventListener element "htmx:beforeOnLoad" receive)
                                           (receive! state response)))]
                           (set! (.-shipyardOwner xhr) (clj->js (current state)))
                           ;; HTMX dispatches beforeOnLoad on the original element,
                           ;; even after a workspace transition detached it.
                           (.addEventListener element "htmx:beforeOnLoad" receive)
                           (.addEventListener xhr "loadend"
                                              #(.removeEventListener element "htmx:beforeOnLoad" receive)))))
    (.addEventListener js/document "htmx:oobBeforeSwap" preserve-drawers!)
    (set! (.-shipyardWorkspace js/window) #js {:current #(clj->js (current state))})
    (.setAttribute (.-body js/document) "data-workspace" "browse")))
