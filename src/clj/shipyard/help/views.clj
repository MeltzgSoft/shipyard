(ns shipyard.help.views
  "Shared control help; long workflows belong in the manual.")

(defn attrs [text]
  {:data-help text :aria-description text})

(defn button [label text]
  [:button.control-help (merge (attrs text) {:type "button" :aria-label (str label " help")})
   [:span {:aria-hidden "true"} "?"]])
