(ns shipyard.workspace.routes
  (:require [shipyard.http.contracts :as contracts]
            [shipyard.workspace.handlers :as handlers]))

(defn routes [deps]
  (into [["/workspace/:mode" {:get {:handler (partial handlers/transition! deps)
                                    :parameters {:path [:map [:mode [:enum "browse" "orient" "assembly" "ships"]]]}
                                    :responses contracts/html-responses}}]
         ["/ships" {:get {:handler (partial handlers/ships! deps) :responses contracts/html-responses}}]]
        (for [mode [:preview :edit :duplicate]]
          [(str "/ships/" (name mode))
           {:post {:handler (partial handlers/transfer! deps mode)
                   :parameters {:form [:map [:id [:and string? [:fn #(some? (parse-uuid %))]]]]}
                   :responses contracts/html-responses}}])))
