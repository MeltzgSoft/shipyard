(ns shipyard.workspace.views
  "Server-rendered workspace controls and stateless HTMX transport guards."
  (:require [clojure.data.json :as json]))

(def navigation-include
  "#filters, #bulk-orient-filters, #part-table-position, .assembly__filters, #ship-filters, #ship-table-position, .assembly__save input[name=name]")

(def transition-attrs
  ;; Keep an accepted transition in flight until its server context is displayed.
  ;; Aborting it could leave the browser holding the preceding generation.
  ;; Disabling a row div does not disable its native selection checkbox.
  ;; Freeze outgoing filter edits, preserving controls already disabled by the view.
  {:hx-swap "innerHTML settle:0ms"
   :hx-sync "#workspace-navigation:drop"
   :hx-disabled-elt "[data-workspace-mode], [data-workspace-transition], [data-bulk-select], [data-workspace-filters] input:enabled, [data-workspace-filters] select:enabled"})

(def transport-attrs
  ;; HTMX reads the current server-rendered context when sending a request.
  ;; The response guard lives on the originating element even when detached.
  ;; This is transport bookkeeping, not a browser workspace store or router.
  {:hx-headers "js:{...JSON.parse(document.getElementById('workspace-context').dataset.headers),'X-Shipyard-Scene-Sequence':document.documentElement.dataset.shipyardSceneSequence||'-1'}"
   :hx-on--config-request
   "if(document.querySelector('[data-ship-inspector-tab=assembly] #assembly')){event.detail.headers['X-Shipyard-Assembly-Scroll']=String(document.getElementById('detail').scrollTop)}"
   :hx-on--before-request
   (str "var elt=event.detail.elt,xhr=event.detail.xhr,generation=event.detail.requestConfig.headers['X-Shipyard-Activation'];"
        "var guard=function(e){if(e.detail.xhr===xhr&&generation!==document.getElementById('workspace-context').dataset.activation){e.preventDefault();}};"
        "elt.addEventListener('htmx:beforeOnLoad',guard);"
        "xhr.addEventListener('loadend',function(){elt.removeEventListener('htmx:beforeOnLoad',guard);});")})

(defn context [{:keys [workspace activation]} colors]
  [:span#workspace-context {:hidden true :hx-swap-oob "outerHTML"
                            :data-workspace (name workspace) :data-activation activation
                            :data-mount-colors (str colors)
                            :data-headers (json/write-str {"X-Shipyard-Workspace" (name workspace)
                                                           "X-Shipyard-Activation" (str activation)})}])

(defn navigation [active]
  [:nav#workspace-navigation.masthead__modes {:aria-label "Workspace modes" :hx-swap-oob "outerHTML"}
   (for [[mode label] [[:browse "Part Browser"] [:ships "Ship Browser"]]]
     [:button.masthead__mode (merge transition-attrs {:type "button" :hx-get (str "/workspace/" (name mode))
                                                      :hx-target "#detail" :hx-swap "innerHTML settle:0ms"
                                                      :hx-include navigation-include
                                                      :data-workspace-mode (name mode)
                                                      :class (when (= mode active) "masthead__mode--active")
                                                      :aria-current (if (= mode active) "page" "false")}) label])])

(defn colors-toggle
  ([colors] (colors-toggle colors nil))
  ([colors workspace]
   [:button#mount-colors-toggle.stage__mount-colors-toggle
    (cond-> {:type "button" :data-mount-colors-toggle "true" :aria-pressed (str colors)
             :hx-post "/workspace/display/colors" :hx-target "this" :hx-swap "outerHTML"
             :hx-swap-oob "outerHTML"}
      (= workspace :browse) (assoc :title (if colors "Switch to layer types" "Switch to mount faces")))
    (if (= workspace :browse)
      (if colors "View: Mount faces" "View: Layer types")
      "Mount colors")]))

(defn ship-editor [tab content]
  [:section.ship-inspector {:data-ship-inspector-tab tab}
   [:button.ship-editor__back (merge transition-attrs
                                     {:type "button" :data-ship-back "true" :data-workspace-transition "true"
                                      :hx-get "/workspace/ships?table=1" :hx-target "#detail"
                                      :hx-include ".assembly__save input[name=name]"}) "← Back to ships"]
   [:h2.ship-editor__title "Assemble"]
   [:nav.paint-segmented {:aria-label "Ship editor"}
    (for [[id label] [["assembly" "Assembly"] ["schemes" "Schemes"] ["paint" "Paint"]]]
      [:button (merge transition-attrs
                      {:type "button" :data-workspace-transition "true" :hx-get (str "/ships/tab/" id)
                       :hx-target "#detail" :hx-include ".assembly__save input[name=name]"
                       :aria-current (when (= id tab) "page")}) label])]
   content])

(defn ship-editor-library []
  [:section#library.panel {:hx-swap-oob "outerHTML" :data-ship-view "editor"}])
