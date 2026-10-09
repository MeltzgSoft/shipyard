(ns shipyard.vocabulary.db
  "Application-wide reusable values in the shared Datalevin store."
  (:require [datalevin.core :as d]
            [shipyard.store.db :as store]
            [shipyard.vocabulary.transforms :as t]
            [shipyard.vocabulary.management :as management]))

(defn registered! [database]
  (store/read! database
               (fn [db]
                 (reduce (fn [m [field value]] (update m field (fnil conj #{}) value))
                         t/builtins
                         (d/q '[:find ?field ?value :where [?e :vocabulary/field ?field]
                                [?e :vocabulary/value ?value]] db)))))

(defn add! [database entry]
  (store/write! database #(d/transact! % [{:vocabulary/key [(:field entry) (:value entry)]
                                           :vocabulary/field (:field entry) :vocabulary/value (:value entry)}])))

(defn choices! [catalog]
  (let [registered (registered! (:store catalog))]
    (store/read! (:store catalog)
                 (fn [db]
                   (let [library (:library @(:state catalog))
                         parts (when library
                                 (d/q '[:find [(pull ?p [:part/bundle :part/bundle-override :part/class :part/class-override
                                                         :part/role-hint :part/role-override]) ...]
                                        :in $ ?library :where [?l :library/id ?library] [?p :part/library ?l]
                                        [?p :part/present? true]] db library))]
                     (reduce (fn [m p]
                               (reduce (fn [m [field inferred authored]]
                                         (if-let [value (or (get p authored) (get p inferred))]
                                           (update m field conj (name value)) m)) m
                                       [[:bundle :part/bundle :part/bundle-override]
                                        [:class :part/class :part/class-override]
                                        [:role :part/role-hint :part/role-override]])) registered parts))))))

(defn roles! [catalog] (mapv keyword (sort (:role (choices! catalog)))))

(defn- management-snapshot [db]
  {:parts (d/q '[:find [(pull ?p [:db/id :part/bundle :part/bundle-override :part/class :part/class-override
                                  :part/role-hint :part/role-override :part/revision
                                  {:part/mounts [:db/id :mount/accepts]}]) ...]
                 :where [?p :part/key]] db)
   :registered (d/q '[:find [(pull ?e [:db/id :vocabulary/field :vocabulary/value]) ...]
                      :where [?e :vocabulary/key]] db)})

(defn entries! [database]
  (store/read! database (fn [db]
                          (let [{:keys [parts registered]} (management-snapshot db)]
                            (management/entries parts registered)))))

(defn inference-values!
  "Registered and effective durable labels across all libraries, including missing parts."
  [database]
  (update-vals (entries! database) #(set (map :value %))))

(defn manage! [database action field old-value new-value]
  (store/write! database
                (fn [connection]
                  (let [{:keys [parts registered]} (management-snapshot @connection)
                        result (management/plan parts registered action field old-value new-value)]
                    (when (seq (:tx result)) (d/transact! connection (:tx result)))
                    (dissoc result :tx)))))
