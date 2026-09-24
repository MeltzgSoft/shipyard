(ns shipyard.http.forms
  "Background commands from the viewport use HTMX's request API, never navigation.")

(defn post! [^js form]
  (let [htmx (.-htmx js/window)]
    (when-not (and htmx (.-isConnected form) (.getAttribute form "hx-post"))
      (throw (js/Error. "The save form is unavailable. Reopen this workspace and retry.")))
    ;; A replacement form can be visible before HTMX's delayed settle processes
    ;; it. Initialize its config-request hooks before issuing the explicit POST.
    (.process htmx form)
    (.ajax htmx "POST" (.getAttribute form "hx-post") #js {:source form})))
