(ns shipyard.workspace.routes
  (:require [shipyard.http.contracts :as contracts]
            [shipyard.assembly.routes :as assembly-routes]
            [shipyard.workspace.handlers :as handlers]))

(def ^:private ship-id-form [:map [:id [:and string? [:fn #(some? (parse-uuid %))]]]])

(defn routes [deps]
  (into [["/workspace/display/colors" {:post {:handler (partial handlers/colors! deps) :responses contracts/html-responses}}]
         ["/workspace/:mode" {:get {:handler (partial handlers/transition! deps)
                                    :parameters {:path [:map [:mode [:enum "browse" "orient" "assembly" "ships"]]]}
                                    :responses contracts/html-responses}}]
         ["/ships" {:get {:handler (partial handlers/ships! deps) :responses contracts/html-responses}}]
         ["/ships/delete" {:post {:handler (partial handlers/delete-ship! deps)
                                  :parameters {:form ship-id-form}
                                  :responses contracts/html-responses}}]]
        (for [mode [:preview :edit :duplicate]]
          [(str "/ships/" (name mode))
           {:post {:handler (partial handlers/transfer! deps mode)
                   :parameters {:form (conj ship-id-form [:discard-revision {:optional true} assembly-routes/revision-schema])}
                   :responses contracts/html-responses}}])))
