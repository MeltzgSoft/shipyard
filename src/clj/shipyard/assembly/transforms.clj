(ns shipyard.assembly.transforms
  "Pure draft transitions and server-derived scene commands."
  (:require [shipyard.assembly.model :as model]
            [shipyard.assembly.scene :as scene]
            [shipyard.catalog.db :as catalog]
            [shipyard.geom :as geom]
            [shipyard.paint.delta :as delta]
            [shipyard.http.urls :as urls]))

(def empty-draft {:revision 0 :hull nil :assignments {}})

(defn transition
  "Validate each mutation against one catalog value and an explicit set of available sources."
  [database draft {:keys [op revision slot part-id allow-other-factions?]} available]
  (let [root (when (:hull draft) (catalog/part database (:hull draft)))
        derived (when root (model/slots database (:hull draft) (:assignments draft) draft))
        target (first (filter #(= slot (:id %)) (:slots derived)))
        candidate (when part-id (catalog/part database part-id))
        error (cond
                (not= revision (:revision draft)) :stale-revision
                (= op :reset) nil
                (= op :hull) (or (model/root-error candidate)
                                 (when-not (available part-id) :unavailable-mesh))
                (nil? (:hull draft)) :no-draft
                (= op :compatibility) (when-not (boolean? allow-other-factions?) :invalid-compatibility)
                (= op :clear) (when-not (contains? (:assignments draft) slot) :stale-slot)
                (not= op :assign) :unknown-operation
                (nil? target) :stale-slot
                :else (or (model/candidate-error root (:parent-role target) (:mount target)
                                                 (:ancestors target) candidate draft)
                          (when-not (available part-id) :unavailable-mesh)))
        next-draft (when-not error
                     (case op
                       :reset (assoc empty-draft :revision (inc revision))
                       :hull {:revision (inc revision) :hull part-id :assignments {}}
                       :compatibility (-> draft (update :revision inc)
                                          (assoc :allow-other-factions? allow-other-factions?))
                       :clear (-> draft
                                  (update :revision inc)
                                  (update :assignments model/prune slot))
                       :assign (-> draft
                                   (update :revision inc)
                                   (update :assignments #(assoc (model/prune % slot) slot part-id)))))
        next-model (when (:hull next-draft)
                     (model/slots database (:hull next-draft) (:assignments next-draft) next-draft))
        assigned-errors (filter #(contains? (:assignments next-draft) (:slot %)) (:errors next-model))
        missing (when (= op :assign)
                  (first (remove available (cons (:hull next-draft) (vals (:assignments next-draft))))))
        error (or error (when (and (= op :compatibility) (not allow-other-factions?)
                                   (some #(= :different-bundle (:code %)) assigned-errors)) :other-factions-in-use)
                  (when (seq assigned-errors) :stale-draft)
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
          {:keys [slots errors]} (model/slots database (:hull draft) (:assignments draft) draft)
          invalid (set (map :slot errors))]
      (if (model/root-error root)
        {}
        (reduce (fn [scene {:keys [id parent mount parent-role assigned]}]
                  (if (and assigned (not (invalid id)) (contains? scene parent))
                    (let [parent-part (catalog/part database (get-in scene [parent :part-id]))
                          part (catalog/part database assigned)
                          child-mount (model/attachment-mount parent-role mount part)]
                      (assoc scene id {:part-id assigned
                                       :matrix (geom/attachment-matrix
                                                (get-in scene [parent :matrix]) mount child-mount
                                                (:part/orientation parent-part)
                                                (:part/orientation part) 0.0)
                                       :mount-position
                                       (geom/transform-point
                                        (get-in scene [parent :matrix])
                                        (:mount/pos mount))}))
                    scene))
                {[] {:part-id (:hull draft) :matrix (geom/orientation-matrix (:part/orientation root))}}
                slots)))))

(def appearance-keys [:material :details :regions :layers])

(defn commands
  "Send geometry only when changed, and appearance patches without unchanged masks."
  [before after mesh-keys reset?]
  (vec
   (concat
    (when reset? [{:op :reset}])
    (when-not reset?
      (for [slot (sort-by pr-str (keys before))
            :when (not= (apply dissoc (get before slot) appearance-keys)
                        (apply dissoc (get after slot) appearance-keys))]
        {:op :remove :slot slot}))
    (keep (fn [[slot {:keys [part-id matrix mount-position material role details regions layers] :as placement}]]
            (when-let [mesh-key (get mesh-keys part-id)]
              (let [previous (get before slot)
                    same-geometry? (and (not reset?) (= mesh-key (:mesh-key previous))
                                        (= (apply dissoc previous appearance-keys)
                                           (apply dissoc placement appearance-keys)))
                    changes (into {} (keep (fn [k] (when (not= (get previous k) (get placement k)) [k (get placement k)]))) appearance-keys)
                    changes (if (and same-geometry? details (contains? changes :details)
                                     (or (nil? (:details previous))
                                         (= (select-keys details [:part-id :mesh-key])
                                            (select-keys (:details previous) [:part-id :mesh-key]))))
                              (-> changes (dissoc :details)
                                  (assoc :detail-delta (assoc (select-keys details [:part-id :mesh-key])
                                                              :patch (delta/between (get-in previous [:details :faces]) (:faces details)))))
                              changes)]
                (if same-geometry?
                  ;; Reassert compact values: the browser may have an unsaved local preview.
                  {:op :paint :slot slot :changes (merge {:material material :layers layers} changes)}
                  {:op :set :slot slot :part-id part-id :matrix matrix
                   :mesh-key mesh-key :url (urls/mesh-url mesh-key 0)
                   :color (:hex (scene/color-for-slot slot))
                   :mount-position mount-position :material material :role role :details details :regions regions :layers layers}))))
          (sort-by (comp pr-str key) after)))))

(defn snapshot-commands
  "Recover a missed baseline with complete ready slots, retaining matching client geometry."
  [after mesh-keys]
  (let [sets (subvec (commands {} after mesh-keys true) 1)]
    (into [{:op :snapshot :slots (mapv :slot sets)}] sets)))

(defn pack-regions
  "A source region mask is shared by all its mounted instances in one envelope."
  [event placements]
  (reduce (fn [event [i command]]
            (let [path (if (= :paint (:op command)) [:changes :regions] [:regions])
                  regions (get-in command path)
                  part-id (get-in placements [(:slot command) :part-id])]
              (if (and part-id (seq (:faces regions)))
                (let [parent (into [:commands i] (butlast path))]
                  (-> event
                      (assoc-in [:region-data part-id] regions)
                      (update-in parent #(-> % (dissoc :regions) (assoc :region-ref part-id)))))
                event))) event (map-indexed vector (:commands event))))

(defn mount-markers
  "World-space marker positions for every reachable mount, including empty slots."
  [database draft]
  (let [placements (try (placements database draft)
                        (catch clojure.lang.ExceptionInfo _ {}))
        placements (if (contains? placements [])
                     placements
                     (if-let [root (catalog/part database (:hull draft))]
                       {[] {:matrix (geom/orientation-matrix (:part/orientation root))}}
                       {}))
        {:keys [slots]} (model/slots database (:hull draft) (:assignments draft) draft)]
    (into {}
          (keep (fn [{:keys [id parent mount]}]
                  (when-let [parent-matrix (get-in placements [parent :matrix])]
                    [id {:mount-position (geom/transform-point parent-matrix (:mount/pos mount))
                         :color (:hex (scene/color-for-slot id))}]))
                slots))))
