(ns shipyard.assembly.model
  "Pure Datascript queries and authored assembly decisions. No filesystem or mutable state."
  (:require [datascript.core :as d]
            [shipyard.catalog.db :as db]
            [shipyard.mount.split :as split]
            [shipyard.mount.wizard :as wizard]))

(defn root-error [part]
  (cond
    (nil? (:part/id part)) :missing-part
    (not (and (:part/renderable part) (:part/source part))) :unavailable-mesh
    (not (and (= :manual (:part/role-source part))
              (#{:hull :hull-section} (:part/role-hint part)))) :unauthored-hull
    :else nil))

(defn candidate-error
  "First authoritative rejection reason, independent of browser hints or scene state."
  [root socket ancestors candidate]
  (let [plugs (filter #(= :plug (:mount/kind %)) (:part/mounts candidate))]
    (cond
      (nil? (:part/id candidate)) :missing-part
      (not (and (:part/renderable candidate) (:part/source candidate))) :unavailable-mesh
      (not= (:part/bundle root) (:part/bundle candidate)) :different-bundle
      (not= (:part/class root) (:part/class candidate)) :different-class
      (not= :manual (:part/role-source candidate)) :unauthored-role
      (not (contains? (set (:mount/accepts socket)) (:part/role-hint candidate))) :incompatible-role
      (not= 1 (count plugs)) :plug-count
      (not (wizard/valid-frame? (first plugs))) :invalid-plug
      (contains? (set ancestors) (:part/id candidate)) :cycle
      :else nil)))

(defn candidates
  "Query authored accepted roles through Datascript; return only valid candidates."
  [database root socket ancestors]
  (->> (d/q '[:find [(pull ?p [*]) ...]
              :in $ ?bundle [?role ...]
              :where [?p :part/bundle ?bundle]
              [?p :part/role-source :manual] [?p :part/role-hint ?role]]
            database (:part/bundle root) (:mount/accepts socket))
       (remove #(candidate-error root socket ancestors %))
       (sort-by :part/id)
       (vec)))

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
  Invalid subtrees are not traversed; independent valid sockets remain visible."
  [database hull-id assignments]
  (let [root (when hull-id (db/part database hull-id))
        errors (fn [code path part-id] {:code code :slot path :part-id part-id})]
    (if-let [error (root-error root)]
      {:slots [] :errors [(errors error [] hull-id)]}
      (loop [pending [[[] root [hull-id]]] result [] problems []]
        (if-let [[parent part ancestors] (first pending)]
          (let [sockets (->> (:part/mounts part)
                             (filter #(= :socket (:mount/kind %)))
                             (sort-by (comp str :mount/id)))
                duplicate-ids (->> sockets (map :mount/id) (frequencies)
                                   (keep (fn [[id n]] (when (> n 1) id))) (set))
                expanded
                (mapv (fn [socket]
                        (let [section (when (wizard/valid-frame? socket) (split/sections socket))
                              error (cond
                                      (or (nil? (:mount/id socket))
                                          (duplicate-ids (:mount/id socket))) :duplicate-mount-id
                                      (not (wizard/valid-frame? socket)) :invalid-socket
                                      (:error section) (:error section))]
                          (if error
                            {:errors [(errors error parent (:part/id part))]}
                            {:slots (mapv (fn [ordinal frame]
                                            (let [id (conj parent [(:mount/id socket) ordinal])]
                                              {:id id :parent parent :part-id (:part/id part)
                                               :socket frame :ancestors ancestors
                                               :assigned (get assignments id)}))
                                          (range) (:frames section))}))) sockets)
                next-slots (vec (mapcat :slots expanded))
                assigned (filter :assigned next-slots)
                checked (mapv (fn [{:keys [id socket assigned] :as slot}]
                                (let [child (db/part database assigned)
                                      error (or (candidate-error root socket ancestors child)
                                                (when (>= (count id) 16) :nesting-limit))]
                                  (if error
                                    {:error (errors error id assigned)}
                                    {:pending [id child (conj (:ancestors slot) assigned)]}))) assigned)]
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
