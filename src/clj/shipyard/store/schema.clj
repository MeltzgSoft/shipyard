(ns shipyard.store.schema
  "Declared storage contract. Domain relationships are refs; dense face payloads
  and small mathematical values are data, never serialized entity documents.")

(defn- attributes [type names]
  (zipmap names (repeat (if (= type :db.type/data) {} {:db/valueType type}))))

(def schema
  (merge
   (attributes :db.type/string
               [:store/key :library/root :part/id :part/name :part/bundle :part/class
                :part/mesh-key :source/path :mesh/sha :layer/id :layer/name :layer/preview-name
                :scheme/name :loadout/name :group/name :fleet/name :migration/path])
   (attributes :db.type/uuid
               [:library/id :part/uid :mount/uid :scheme/id :loadout/id :group/id :fleet/id])
   (attributes :db.type/long
               [:store/version :library/revision :part/revision :part/tris :source/size
                :source/mtime :mesh/tris :region/revision :chunk/index :chunk/version
                :scheme/revision :loadout/revision :group/order :membership/order :fleet-entry/order :mount/capacity :mount/order])
   (attributes :db.type/boolean
               [:source/present? :part/present? :part/renderable :part/weapons? :part/turrets?
                :part/accepts-turrets? :layer/deleted? :scheme/deleted? :loadout/deleted?
                :part/imported? :library/imported? :store/imported? :layer/builtin?])
   (attributes :db.type/keyword
               [:part/source :part/role-hint :part/role-source :part/role-override :mount/id :mount/kind
                :mount/origin :mount/mirror-id :source/variant :binding/role])
   (attributes :db.type/data
               [:part/orientation :mount/pos :mount/axis :mount/roll :mount/magnet
                :mount/facet :mount/split :mount/mirror-plane :mount/mirror-offset :chunk/payload
                :slot/path :target/path :material/base :material/metalness
                :material/roughness :material/paint :scheme/fields :migration/extra])
   (attributes :db.type/ref
               [:part/library :source/part :source/content :layer/library :mask/layer
                :region/content :slot/part :loadout/hull :loadout/library :loadout/scheme :scheme/library
                :membership/target :binding/layer :target/part :detail/content :fleet/default-scheme
                :fleet-entry/loadout :mount/mirror])
   (into {} (map (fn [attr] [attr {:db/valueType :db.type/ref :db/isComponent true}]))
         [:part/regions :target/details])
   (into {} (map (fn [attr] [attr {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many
                                   :db/isComponent true}]))
         [:part/mounts :part/sources :region/masks :mask/chunks :detail/chunks
          :scheme/roles :scheme/layers :scheme/targets :scheme/groups :loadout/slots
          :fleet/entries :group/members])
   {:mount/accepts {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
    :part/variants {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}}
   (into {} (map (fn [attr] [attr {:db/valueType :db.type/uuid :db/unique :db.unique/identity}]))
         [:library/id :part/uid :mount/uid :scheme/id :loadout/id :group/id :fleet/id])
   (into {} (map (fn [attr] [attr {:db/valueType :db.type/string :db/unique :db.unique/identity}]))
         [:store/key :library/root :mesh/sha :migration/path])
   (into {} (map (fn [attr] [attr {:db/unique :db.unique/identity}]))
         [:part/key :layer/key :source/key])
   {:layer/active-name {:db/unique :db.unique/value}}))
