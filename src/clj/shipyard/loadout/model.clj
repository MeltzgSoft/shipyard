(ns shipyard.loadout.model
  "Pure saved-ship validation and explicit draft transfers."
  (:require [shipyard.assembly.model :as assembly]
            [shipyard.assembly.transforms :as draft]
            [shipyard.catalog.db :as catalog]
            [shipyard.loadout.transforms :as store]))

(defn validate
  [database {:keys [hull assignments] :as value} available]
  (let [parts (cons hull (vals assignments))
        derived (assembly/slots database hull assignments)
        error (or (when-not hull :no-draft)
                  (some (fn [id]
                          (let [part (catalog/part database id)]
                            (cond
                              (nil? part) :missing-part
                              (not (available id)) :unavailable-mesh))) parts)
                  (some-> derived :errors first :code))]
    (if error
      {:error error :diagnostics (:errors derived)}
      (try
        {:draft value :scene (draft/placements database value)}
        (catch clojure.lang.ExceptionInfo e {:error (or (:code (ex-data e)) :stale-draft)})))))

(defn from-record [record revision mode]
  (cond-> {:revision revision :hull (:loadout/hull record) :assignments (:loadout/slots record)
           :name (str (:loadout/name record) (when (= :duplicate mode) " - Copy"))}
    (not= :duplicate mode) (assoc :loadout-id (:loadout/id record))
    (:loadout/scheme record) (assoc :scheme (:loadout/scheme record))))

(defn to-record [draft id name]
  (when (store/name? name)
    (cond-> {:loadout/id id :loadout/name name :loadout/hull (:hull draft)
             :loadout/slots (:assignments draft)}
      (:scheme draft) (assoc :loadout/scheme (:scheme draft)))))

(defn empty-mount-count
  "Count reachable, unassigned mounts; invalid trees have no reliable count."
  [database {:loadout/keys [hull slots]}]
  (let [derived (assembly/slots database hull slots)]
    (when-not (seq (:errors derived))
      (count (remove :assigned (:slots derived))))))

(defn listing [database records {:keys [bundle class]}]
  (->> (vals records)
       (map (fn [record]
              (let [hull (catalog/part database (:loadout/hull record))]
                {:loadout record :bundle (:part/bundle hull) :class (:part/class hull)
                 :missing? (nil? hull) :empty-mounts (empty-mount-count database record)})))
       (filter #(and (or (not (seq bundle)) (= bundle (:bundle %)))
                     (or (not (seq class)) (= class (:class %)))))
       (sort-by (juxt #(get-in % [:loadout :loadout/name]) #(str (get-in % [:loadout :loadout/id]))))
       (vec)))

(defn- compare-slot-paths [left right]
  (loop [left (seq left) right (seq right)]
    (cond
      (nil? left) (if (nil? right) 0 -1)
      (nil? right) 1
      :else (let [order (compare (first left) (first right))]
              (if (zero? order)
                (recur (next left) (next right))
                order)))))

(defn part-tree
  "Hull first, then each slot's complete subtree before its next sibling."
  [{:keys [hull assignments]}]
  (if hull
    (vec (sort-by key compare-slot-paths (assoc assignments [] hull)))
    []))

(defn after-delete
  "Clear the deleted preview; retain editing work as a new, unsaved draft."
  [value id target]
  (if (= id (:loadout-id value))
    (case target
      :preview (assoc draft/empty-draft :revision (inc (:revision value)))
      :assembly (-> value (dissoc :loadout-id) (update :revision inc)))
    value))

(defn unsaved?
  "Compare draft content with its durable record; revisions and render state are irrelevant."
  [value records]
  (boolean
   (and (:hull value)
        (let [id (:loadout-id value) record (get records id)]
          (or (nil? record)
              (not= record (to-record value id (:name value))))))))
