(ns shipyard.assembly.transforms
  "Pure draft transitions and server-derived scene commands."
  (:require [shipyard.assembly.model :as model]
            [shipyard.catalog.db :as catalog]
            [shipyard.geom :as geom]
            [shipyard.http.urls :as urls]))

(def empty-draft {:revision 0 :hull nil :assignments {}})

(defn transition
  "Validate each mutation against one catalog value and an explicit set of available sources."
  [database draft {:keys [op revision slot part-id]} available]
  (let [root (when (:hull draft) (catalog/part database (:hull draft)))
        derived (when root (model/slots database (:hull draft) (:assignments draft)))
        target (first (filter #(= slot (:id %)) (:slots derived)))
        candidate (when part-id (catalog/part database part-id))
        error (cond
                (not= revision (:revision draft)) :stale-revision
                (= op :reset) nil
                (= op :hull) (or (model/root-error candidate)
                                 (when-not (available part-id) :unavailable-mesh))
                (nil? (:hull draft)) :no-draft
                (= op :clear) (when-not (contains? (:assignments draft) slot) :stale-slot)
                (not= op :assign) :unknown-operation
                (nil? target) :stale-slot
                :else (or (model/candidate-error root (:parent-role target) (:mount target)
                                                 (:ancestors target) candidate)
                          (when-not (available part-id) :unavailable-mesh)))
        next-draft (when-not error
                     (case op
                       :reset (assoc empty-draft :revision (inc revision))
                       :hull {:revision (inc revision) :hull part-id :assignments {}}
                       :clear (-> draft
                                  (update :revision inc)
                                  (update :assignments model/prune slot))
                       :assign (-> draft
                                   (update :revision inc)
                                   (update :assignments #(assoc (model/prune % slot) slot part-id)))))
        next-model (when (:hull next-draft)
                     (model/slots database (:hull next-draft) (:assignments next-draft)))
        assigned-errors (filter #(contains? (:assignments next-draft) (:slot %)) (:errors next-model))
        missing (when (= op :assign)
                  (first (remove available (cons (:hull next-draft) (vals (:assignments next-draft))))))
        error (or error (when (seq assigned-errors) :stale-draft)
                  (when missing :unavailable-mesh))]
    (if error
      {:draft draft :error error :diagnostics (vec assigned-errors)
       :status (if (#{:stale-revision :stale-slot :stale-draft} error) 409 422)}
      {:draft next-draft :diagnostics (vec (:errors next-model)) :status 200})))

(defn placements
  "Slot -> source mesh placement. Nested transforms are fully composed on the server."
  [database draft]
  (if-not (:hull draft)
    {}
    (let [root (catalog/part database (:hull draft))
          {:keys [slots errors]} (model/slots database (:hull draft) (:assignments draft))
          invalid (set (map :slot errors))]
      (if (model/root-error root)
        {}
        (reduce (fn [scene {:keys [id parent mount parent-role assigned]}]
                  (if (and assigned (not (invalid id)) (contains? scene parent))
                    (let [part (catalog/part database assigned)
                          child-mount (model/attachment-mount parent-role mount part)]
                      (assoc scene id {:part-id assigned
                                       :matrix (geom/attachment-matrix
                                                (get-in scene [parent :matrix]) mount child-mount
                                                (:part/orientation part) 0.0)}))
                    scene))
                {[] {:part-id (:hull draft) :matrix (geom/orientation-matrix (:part/orientation root))}}
                slots)))))

(defn commands
  "Render-ready sets and explicit removals. Repeated unchanged sets may be retained by the viewport."
  [before after mesh-keys reset?]
  (vec
   (concat
    (when reset? [{:op :reset}])
    (when-not reset?
      (for [slot (sort-by pr-str (keys before)) :when (not= (get before slot) (get after slot))]
        {:op :remove :slot slot}))
    (for [[slot {:keys [part-id matrix]}] (sort-by (comp pr-str key) after)
          :let [mesh-key (get mesh-keys part-id)]
          :when mesh-key]
      {:op :set :slot slot :part-id part-id :matrix matrix
       :mesh-key mesh-key :url (urls/mesh-url mesh-key 0)}))))
