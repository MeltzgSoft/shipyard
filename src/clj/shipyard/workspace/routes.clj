(ns shipyard.workspace.routes
  (:require [shipyard.http.contracts :as contracts]
            [shipyard.assembly.routes :as assembly-routes]
            [shipyard.paint.handlers :as paint]
            [shipyard.loadout.thumbnail :as thumbnail]
            [shipyard.scheme.presets :as presets]
            [shipyard.workspace.handlers :as handlers]))

(def ^:private ship-id-form [:map [:id [:and string? [:fn #(some? (parse-uuid %))]]]])

(defn routes [deps]
  (into [["/ship-thumbnails/:kind/:id" {:get {:handler (partial thumbnail/thumbnail! deps)
                                              :parameters {:path [:map [:kind [:enum "class" "ship"]] [:id [:and string? [:fn #(some? (parse-uuid %))]]]]}
                                              :responses contracts/html-responses}}]
         ["/workspace/display/colors" {:post {:handler (partial handlers/colors! deps) :responses contracts/html-responses}}]
         ["/workspace/:mode" {:get {:handler (partial handlers/transition! deps)
                                    :parameters {:path [:map [:mode [:enum "browse" "ships"]]]}
                                    :responses contracts/html-responses}}]
         ["/ships/tab/:tab" {:get {:handler (partial handlers/ship-tab! deps)
                                   :parameters {:path [:map [:tab [:enum "assembly" "schemes" "paint"]]]}
                                   :responses contracts/html-responses}}]
         ["/ships" {:get {:handler (partial handlers/ships! deps) :responses contracts/html-responses}}]
         ["/ships/hulls/:id" {:get {:handler (partial handlers/named-rows! deps)
                                    :parameters {:path ship-id-form}
                                    :responses contracts/html-responses}}]
         ["/ships/new" {:post {:handler (partial handlers/new-ship! deps)
                               :parameters {:form [:map [:discard-revision {:optional true} assembly-routes/revision-schema]]}
                               :responses contracts/html-responses}}]
         ["/ships/open" {:post {:handler (partial handlers/open-ship! deps)
                                :parameters {:form (conj ship-id-form [:kind [:enum "class" "ship"]]
                                                         [:discard-revision {:optional true} assembly-routes/revision-schema])}
                                :responses contracts/html-responses}}]
         ["/ships/delete" {:post {:handler (partial handlers/delete-ship! deps)
                                  :parameters {:form ship-id-form}
                                  :responses contracts/html-responses}}]]
        (concat
         (for [operation [:add :remove]]
           [(str "/ships/schemes/presets/" (name operation))
            {:post {:handler (partial presets/handle! deps operation)
                    :parameters {:form [:map [:base [:re "#[0-9a-fA-F]{6}"]]]}
                    :responses contracts/html-responses}}])
         (for [action [:select :layer :create :rename :delete :material]]
           [(str "/ships/schemes/" (name action)) {:post {:handler (partial handlers/scheme-change! deps action) :responses contracts/html-responses}}])
         [["/ships/paint/stroke" {:post {:handler (partial paint/stroke! deps) :responses contracts/html-responses}}]
          ["/ships/paint/delete" {:post {:handler (partial handlers/paint-selection! deps :delete)
                                         :parameters {:form (conj ship-id-form [:confirmed [:enum "true"]])}
                                         :responses contracts/html-responses}}]
          ["/ships/paint/material" {:post {:handler (partial paint/material! deps) :responses contracts/html-responses}}]]
         (for [action [:create :rename :delete :members :order]]
           [(str "/ships/paint/group/" (name action))
            {:post {:handler (partial handlers/paint-selection! deps (keyword (str "group-" (name action))))
                    :parameters {:form (into [:map]
                                             (concat (when (not= action :create) [[:group [:and string? [:fn #(some? (parse-uuid %))]]]])
                                                     (when (#{:create :rename} action) [[:name string?]])
                                                     (when (#{:create :members} action) [[:members {:optional true} [:or string? [:sequential string?]]]])
                                                     (when (= action :order) [[:direction [:enum "up" "down"]]])))}
                    :responses contracts/html-responses}}])
         (for [action [:select :target :create :rename :default :reset :tool]]
           [(str "/ships/paint/" (name action)) {:post {:handler (partial handlers/paint-selection! deps action)
                                                        :responses contracts/html-responses}}])
         (for [source [:assembly :ships]]
           [(str "/" (name source) "/paint") {:post {:handler (partial handlers/paint-transfer! deps source)
                                                     :responses contracts/html-responses}}])
         (for [mode [:edit :duplicate]]
           [(str "/ships/" (name mode))
            {:post {:handler (partial handlers/transfer! deps mode)
                    :parameters {:form (conj ship-id-form [:discard-revision {:optional true} assembly-routes/revision-schema])}
                    :responses contracts/html-responses}}]))))
