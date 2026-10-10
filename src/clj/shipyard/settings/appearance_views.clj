(ns shipyard.settings.appearance-views
  "Application appearance projection shared by the shell and settings responses.")

(defn state
  ([theme] (state theme false))
  ([theme oob?]
   [:span#app-appearance {:hidden true :data-theme (name theme)
                          :hx-swap-oob (when oob? "outerHTML")}]))
