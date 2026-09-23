(ns shipyard.workspace.views
  "Server-rendered workspace controls and stateless HTMX transport guards."
  (:require [clojure.data.json :as json]))

(def navigation-include
  "#filters, #bulk-orient-filters, .assembly__filters, #ship-filters, .assembly__save input[name=name]")

(def transition-attrs
  ;; Keep an accepted transition in flight until its server context is displayed.
  ;; Aborting it could leave the browser holding the preceding generation.
  {:hx-sync "#workspace-navigation:drop"
   :hx-disabled-elt "[data-workspace-mode], [data-workspace-transition]"})

(def transport-attrs
  ;; HTMX reads the current server-rendered context when sending a request.
  ;; The response guard lives on the originating element even when detached.
  ;; This is transport bookkeeping, not a browser workspace store or router.
  {:hx-headers "js:{...JSON.parse(document.getElementById('workspace-context').dataset.headers)}"
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
   (for [[mode label] [[:orient "Orient"] [:browse "Part Browser"] [:assembly "Assemble"] [:ships "Ship Browser"] [:paint "Paint"]]]
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
