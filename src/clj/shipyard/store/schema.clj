(ns shipyard.store.schema
  "Declared storage contract. Domain relationships are refs; dense face payloads
  and small mathematical values are data, never serialized entity documents.")

(defn- attributes [type names]
  (zipmap names (repeat (if (= type :db.type/data) {} {:db/valueType type}))))

(def schema
  (merge
   (attributes :db.type/string
               [:store/key :settings/library-root :scan/root :scan/part-id :scan/mesh-key
                :library/root :part/id :part/name :part/bundle :part/class
                :part/name-override :part/bundle-override :part/class-override
                :part/mesh-key :source/path :mesh/sha :layer/id :layer/name :layer/preview-name
                :scheme/name :ship/name :loadout/name :group/name :fleet/name :vocabulary/value])
   (attributes :db.type/uuid
               [:library/id :part/uid :mount/uid :scheme/id :ship/id :loadout/id :paint/group-id :fleet/id])
   (attributes :db.type/long
               [:store/version :scan/mtime :scan/size :scan/tris :library/revision :part/revision :part/tris :source/size
                :source/mtime :mesh/tris :region/revision :chunk/index :chunk/version
                :scheme/revision :ship/revision :loadout/revision :group/order :membership/order :fleet-entry/order :mount/capacity :mount/order])
   (attributes :db.type/boolean
               [:source/present? :part/present? :part/renderable :part/weapons? :part/turrets?
                :part/accepts-turrets? :layer/deleted? :scheme/deleted? :loadout/deleted? :loadout/allow-other-factions?
                :ship/deleted? :layer/builtin?])
   (attributes :db.type/keyword
               [:part/source :part/role-hint :part/role-source :part/role-override :mount/id :mount/kind
                :mount/origin :mount/mirror-id :source/variant :vocabulary/field])
   (attributes :db.type/data
               [:settings/mount-cut-defaults :scan/escort-analysis :part/orientation :mount/pos :mount/axis :mount/roll :mount/magnet
                :mount/facet :mount/split :mount/cut :mount/outline :mount/mirror-plane :mount/mirror-offset :chunk/payload
                :slot/path :target/path :layer/preview-color :material/base :material/metalness
                :material/roughness :material/glow :material/paint :paint/fields])
   (attributes :db.type/ref
               [:part/library :source/part :source/content :layer/library :mask/layer
                :region/content :slot/part :ship/class :ship/scheme :ship/library :loadout/hull :loadout/library :scheme/library
                :membership/target :binding/layer :target/part :detail/content :fleet/default-scheme
                :fleet-entry/loadout :mount/mirror])
   (into {} (map (fn [attr] [attr {:db/valueType :db.type/ref :db/isComponent true}]))
         [:part/regions :target/details :ship/paint])
   (into {} (map (fn [attr] [attr {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many
                                   :db/isComponent true}]))
         [:part/mounts :part/sources :region/masks :mask/chunks :detail/chunks
          :scheme/layers :loadout/slots
          :paint/layers :paint/targets :paint/groups
          :fleet/entries :group/members])
   {:mount/accepts {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
    :part/variants {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}}
   (into {} (map (fn [attr] [attr {:db/valueType :db.type/uuid :db/unique :db.unique/identity}]))
         [:library/id :part/uid :mount/uid :scheme/id :ship/id :loadout/id :fleet/id])
   (into {} (map (fn [attr] [attr {:db/valueType :db.type/string :db/unique :db.unique/identity}]))
         [:store/key :library/root :mesh/sha :color-preset/hex])
   (into {} (map (fn [attr] [attr {:db/unique :db.unique/identity}]))
         [:scan/key :part/key :layer/key :source/key :vocabulary/key])
   {:layer/active-name {:db/unique :db.unique/value}}))
