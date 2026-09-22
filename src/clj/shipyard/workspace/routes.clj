(ns shipyard.workspace.routes
  (:require [shipyard.http.contracts :as contracts]
            [shipyard.assembly.routes :as assembly-routes]
            [shipyard.paint.handlers :as paint]
            [shipyard.workspace.handlers :as handlers]))

(def ^:private ship-id-form [:map [:id [:and string? [:fn #(some? (parse-uuid %))]]]])

(defn routes [deps]
  (into [["/workspace/display/colors" {:post {:handler (partial handlers/colors! deps) :responses contracts/html-responses}}]
         ["/workspace/:mode" {:get {:handler (partial handlers/transition! deps)
                                    :parameters {:path [:map [:mode [:enum "browse" "orient" "assembly" "ships" "paint"]]]}
                                    :responses contracts/html-responses}}]
         ["/ships" {:get {:handler (partial handlers/ships! deps) :responses contracts/html-responses}}]
         ["/ships/delete" {:post {:handler (partial handlers/delete-ship! deps)
                                  :parameters {:form ship-id-form}
                                  :responses contracts/html-responses}}]]
        (concat
         [["/paint/stroke" {:post {:handler (partial paint/stroke! deps) :responses contracts/html-responses}}]
          ["/paint/delete" {:post {:handler (partial handlers/paint-selection! deps :delete)
                                   :parameters {:form (conj ship-id-form [:confirmed [:enum "true"]])}
                                   :responses contracts/html-responses}}]
          ["/paint" {:get {:handler (partial paint/current! deps) :responses contracts/html-responses}}]
          ["/paint/material" {:post {:handler (partial paint/material! deps) :responses contracts/html-responses}}]]
         (for [action [:create :rename :delete :members :order]]
           [(str "/paint/group/" (name action))
            {:post {:handler (partial handlers/paint-selection! deps (keyword (str "group-" (name action))))
                    :parameters {:form (into [:map]
                                             (concat (when (not= action :create) [[:group [:and string? [:fn #(some? (parse-uuid %))]]]])
                                                     (when (#{:create :rename} action) [[:name string?]])
                                                     (when (#{:create :members} action) [[:members {:optional true} [:or string? [:sequential string?]]]])
                                                     (when (= action :order) [[:direction [:enum "up" "down"]]])))}
                    :responses contracts/html-responses}}])
         (for [action [:select :target :create :rename :default :tool]]
           [(str "/paint/" (name action)) {:post {:handler (partial handlers/paint-selection! deps action)
                                                  :responses contracts/html-responses}}])
         (for [source [:assembly :ships]]
           [(str "/" (name source) "/paint") {:post {:handler (partial handlers/paint-transfer! deps source)
                                                     :responses contracts/html-responses}}])
         (for [mode [:preview :edit :duplicate]]
           [(str "/ships/" (name mode))
            {:post {:handler (partial handlers/transfer! deps mode)
                    :parameters {:form (conj ship-id-form [:discard-revision {:optional true} assembly-routes/revision-schema])}
                    :responses contracts/html-responses}}]))))
