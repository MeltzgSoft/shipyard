(ns shipyard.assembly.model
  "Pure catalog queries and authored assembly decisions. No filesystem or mutable state."
  (:require [shipyard.catalog.db :as db]
            [shipyard.mount.split :as split]
            [shipyard.mount.wizard :as wizard]))

(defn root-error [part]
  (cond
    (nil? (:part/id part)) :missing-part
    (not (and (:part/renderable part) (:part/source part))) :unavailable-mesh
    (not (#{:hull :hull-section} (:part/role-hint part))) :unauthored-hull
    :else nil))

(defn- attachment-mounts
  "The candidate frames that can mate with an authored parent frame.

  A socket on the assembled parent receives a candidate plug. Conversely, a
  plug on the assembled parent receives a candidate socket that explicitly
  accepts the parent's role."
  [parent-role parent-mount candidate]
  (case (:mount/kind parent-mount)
    :socket (filter #(= :plug (:mount/kind %)) (:part/mounts candidate))
    :plug (filter #(and (= :socket (:mount/kind %))
                        (contains? (set (:mount/accepts %))
                                   parent-role))
                  (:part/mounts candidate))
    []))

(defn attachment-mount
  "The sole candidate frame that mates with `parent-mount`, otherwise nil."
  [parent-role parent-mount candidate]
  (let [mounts (attachment-mounts parent-role parent-mount candidate)]
    (when (= 1 (count mounts))
      (first mounts))))

(defn candidate-error
  "First authoritative rejection reason, independent of browser hints or scene state."
  [root parent-role parent-mount ancestors candidate]
  (let [attachment-mounts (attachment-mounts parent-role parent-mount candidate)
        socket? (= :socket (:mount/kind parent-mount))]
    (cond
      (nil? (:part/id candidate)) :missing-part
      (not (and (:part/renderable candidate) (:part/source candidate))) :unavailable-mesh
      (not= (:part/bundle root) (:part/bundle candidate)) :different-bundle
      (not= (:part/class root) (:part/class candidate)) :different-class
      (and socket? (not (contains? (set (:mount/accepts parent-mount)) (:part/role-hint candidate)))) :incompatible-role
      (not= 1 (count attachment-mounts)) (if socket? :plug-count :socket-count)
      (not (wizard/valid-frame? (first attachment-mounts))) (if socket? :invalid-plug :invalid-socket)
      (contains? (set ancestors) (:part/id candidate)) :cycle
      :else nil)))

(defn candidates
  "Select accepted part roles from the immutable catalog; return only valid candidates."
  [database root parent-role parent-mount ancestors]
  (let [parts (db/browse database {:bundle (:part/bundle root)})]
    (->> parts
         (remove #(candidate-error root parent-role parent-mount ancestors %))
         (sort-by :part/id)
         (vec))))

(defn descendant?
  "Whether child is strictly below parent; paths use stable mount ids and ordinals."
  [parent child]
  (and (< (count parent) (count child))
       (= parent (subvec child 0 (count parent)))))

(defn prune
  "Remove the assignment at a path and all its descendants."
  [assignments path]
  (into {} (remove (fn [[id _]] (or (= path id) (descendant? path id)))) assignments))

(defn slots
  "Enumerate reachable slot instances deterministically, diagnosing stale/cyclic authoring.
  Invalid subtrees are not traversed; independent valid mount faces remain visible."
  [database hull-id assignments]
  (let [root (when hull-id (db/part database hull-id))
        errors (fn [code path part-id] {:code code :slot path :part-id part-id})]
    (if-let [error (root-error root)]
      {:slots [] :errors [(errors error [] hull-id)]}
      (loop [pending [[[] root [hull-id] nil]] result [] problems []]
        (if-let [[parent part ancestors upstream-role] (first pending)]
          (let [mounts (->> (:part/mounts part)
                            (filter #(or (= :socket (:mount/kind %))
                                         (and (empty? parent) (= :plug (:mount/kind %)))))
                            (remove #(and upstream-role
                                          (= :socket (:mount/kind %))
                                          (contains? (set (:mount/accepts %)) upstream-role)))
                            (sort-by (comp str :mount/id)))
                duplicate-ids (->> mounts (map :mount/id) (frequencies)
                                   (keep (fn [[id n]] (when (> n 1) id))) (set))
                expanded
                (mapv (fn [mount]
                        (let [socket? (= :socket (:mount/kind mount))
                              section (when (wizard/valid-frame? mount)
                                        (if socket? (split/sections mount) {:frames [mount]}))
                              error (cond
                                      (or (nil? (:mount/id mount))
                                          (duplicate-ids (:mount/id mount))) :duplicate-mount-id
                                      (not (wizard/valid-frame? mount)) (if socket? :invalid-socket :invalid-plug)
                                      (:error section) (:error section))]
                          (if error
                            {:errors [(errors error parent (:part/id part))]}
                            {:slots (mapv (fn [ordinal frame]
                                            (let [id (conj parent [(:mount/id mount) ordinal])]
                                              {:id id :parent parent :part-id (:part/id part)
                                               :mount frame :parent-role (:part/role-hint part)
                                               :ancestors ancestors
                                               :assigned (get assignments id)}))
                                          (range) (:frames section))}))) mounts)
                next-slots (vec (mapcat :slots expanded))
                assigned (filter :assigned next-slots)
                checked (mapv (fn [{:keys [id mount parent-role assigned] :as slot}]
                                (let [child (db/part database assigned)
                                      error (or (candidate-error root parent-role mount ancestors child)
                                                (when (>= (count id) 16) :nesting-limit))]
                                  (if error
                                    {:error (errors error id assigned)}
                                    {:pending [id child (conj (:ancestors slot) assigned)
                                               (:part/role-hint part)]})))
                              assigned)]
            (if (> (+ (count result) (count next-slots)) 4096)
              {:slots result :errors (conj problems (errors :slot-limit parent (:part/id part)))}
              (recur (into (vec (rest pending)) (keep :pending checked))
                     (into result next-slots)
                     (into problems (concat (mapcat :errors expanded) (keep :error checked))))))
          (let [reachable (set (map :id result))]
            {:slots result
             :errors (into problems
                           (for [[path part-id] (sort-by (comp pr-str key) assignments)
                                 :when (not (reachable path))]
                             (errors :stale-slot path part-id)))}))))))
