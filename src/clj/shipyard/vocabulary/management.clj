(ns shipyard.vocabulary.management
  "Pure classification usage projection and atomic edit plans."
  (:require [shipyard.vocabulary.transforms :as t]))

(def fields {:bundle [:part/bundle :part/bundle-override]
             :class [:part/class :part/class-override]
             :role [:part/role-hint :part/role-override]})

(defn effective [part field]
  (let [[inferred authored] (fields field)]
    (some-> (or (get part authored) (get part inferred)) name)))

(defn entries [parts registered]
  (into {}
        (for [field [:bundle :class :role]
              :let [part-counts (frequencies (keep #(effective % field) parts))
                    socket-counts (when (= field :role)
                                    (frequencies (map name (mapcat :mount/accepts (mapcat :part/mounts parts)))))
                    builtins (get t/builtins field)]]
          [field (mapv (fn [value] {:value value :parts (get part-counts value 0)
                                    :sockets (get socket-counts value 0) :builtin? (contains? builtins value)})
                       (sort (into (set (concat (keys part-counts) (keys socket-counts) builtins))
                                   (map :vocabulary/value (filter #(= field (:vocabulary/field %)) registered)))))])))

(defn- registration [field value]
  {:vocabulary/key [field value] :vocabulary/field field :vocabulary/value value})

(defn plan [parts registered action field old-value new-value]
  (let [{:keys [error field value]} (t/entry field (if (= action :add) new-value old-value))
        old value
        destination (t/entry (some-> field name) new-value)
        rows (get (entries parts registered) field)
        existing (some #(when (= old (:value %)) %) rows)
        old-entity (some #(when (and (= field (:vocabulary/field %)) (= old (:vocabulary/value %))) %) registered)]
    (cond
      error {:error error}
      (= action :add) {:tx [(registration field value)]}
      (nil? existing) {:error "This value no longer exists. Refresh Settings."}
      (:builtin? existing) {:error "Built-in classification values cannot be renamed or deleted."}
      (= action :delete)
      (if (pos? (+ (:parts existing) (:sockets existing)))
        {:error "This value is in use. Rename it to update its uses; only unused values can be deleted."}
        {:tx (when old-entity [[:db/retractEntity (:db/id old-entity)]])})
      (:error destination) {:error (:error destination)}
      (= old (:value destination)) {:tx []}
      (some #(= (:value destination) (:value %)) rows) {:error "That value already exists. Choose a different name."}
      :else
      (let [new (:value destination)
            stored-old (if (= field :role) (keyword old) old)
            stored-new (if (= field :role) (keyword new) new)
            authored (second (fields field))]
        {:field field :old old :new new
         :tx (into (cond-> [(registration field new)]
                     old-entity (conj [:db/retractEntity (:db/id old-entity)]))
                   (mapcat (fn [part]
                             (let [label? (= old (effective part field))
                                   sockets (when (= field :role)
                                             (filter #(contains? (set (:mount/accepts %)) stored-old) (:part/mounts part)))]
                               (when (or label? (seq sockets))
                                 (concat [(cond-> {:db/id (:db/id part) :part/revision (inc (or (:part/revision part) 0))}
                                            label? (assoc authored stored-new))]
                                         (mapcat (fn [mount]
                                                   [[:db/retract (:db/id mount) :mount/accepts stored-old]
                                                    [:db/add (:db/id mount) :mount/accepts stored-new]]) sockets))))) parts))}))))
