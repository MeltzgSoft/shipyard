(ns shipyard.domain.schemas
  "Shared domain shapes, independent of HTTP, editors, and persistence.
  Custom constraints express relationships or numeric properties, not map shapes."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [shipyard.math :as math]))

(def finite-number [:fn math/finite-number?])
(def unit-number [:and finite-number [:>= 0] [:<= 1]])
(def rgb [:vector {:min 3 :max 3} unit-number])
(def display-name [:and [:string {:min 1 :max 200}] [:fn (complement str/blank?)]])
(def part-id [:string {:min 1 :max 2048}])
(def slot-path [:vector {:min 1 :max 16} [:tuple :keyword [:and integer? [:>= 0] [:<= 255]]]])
;; Equality with [] historically also accepts an empty sequential root value.
(def instance-path [:or [:= []] slot-path])
(def mesh-key [:and [:string {:min 64 :max 64}] [:re #"^[0-9a-f]{64}$"]])
(def face-key [:and [:string {:min 72 :max 72}] [:re #"^[0-9a-f]{72}$"]])
(def face-keys [:vector {:min 1} face-key])
(def detail-id [:and [:string {:min 42 :max 42}] [:re #"^layer:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"]])
(def builtins ["Primary" "Secondary"])
(def layer-id [:or [:enum "Primary" "Secondary"] detail-id])
(def layer-name [:and [:string {:min 1 :max 200}] [:fn #(= % (str/trim %))]])

(def detail-material
  [:map {:closed true} [:base rgb] [:metalness unit-number] [:roughness unit-number]
   [:glow {:optional true} unit-number]])
(def material
  (conj detail-material [:paint {:optional true} [:string {:max 200}]]))
(def detail-layer
  [:map {:closed true} [:part-id part-id] [:mesh-key mesh-key]
   [:faces [:map-of face-key detail-material]]])
(def instance
  [:map {:closed true} [:part-id part-id] [:material material]])
(def member
  [:map {:closed true} [:path instance-path] [:part-id part-id]])
(defn- distinct-values? [values] (= (count values) (count (set values))))
(def group
  [:map {:closed true} [:group/id :uuid] [:group/name display-name] [:group/order nat-int?]
   [:group/members [:and [:vector member] [:fn distinct-values?]]]
   [:group/material {:optional true} material]])
(def groups
  [:and [:vector group]
   [:fn #(distinct-values? (map :group/id %))]
   [:fn #(distinct-values? (map :group/order %))]])
(def palette [:map-of layer-id material])
(def paint-job
  [:map {:closed true} [:paint/layers {:optional true} palette] [:paint/groups {:optional true} groups]
   [:paint/instances {:optional true} [:map-of {:max 4097} instance-path instance]]
   [:paint/details {:optional true} [:map-of {:max 4097} instance-path detail-layer]]])
(def scheme
  [:map {:closed true} [:scheme/id :uuid] [:scheme/name display-name] [:scheme/layers palette]])
(def loadout
  [:map {:closed true} [:loadout/id :uuid] [:loadout/name display-name] [:loadout/hull part-id]
   [:loadout/slots [:map-of {:max 4096} slot-path part-id]]])
(def ship
  [:map {:closed true} [:ship/id :uuid] [:ship/name display-name] [:ship/class :uuid]
   [:ship/scheme {:optional true} :uuid] [:ship/paint paint-job]])

;; Per-part layer definitions remain open.
(def layer-definition
  [:map [:name layer-name] [:preview-name layer-name] [:preview-color {:optional true} rgb]])
(def regions
  [:and
   [:map {:closed true} [:version [:= 2]] [:mesh-key mesh-key] [:revision nat-int?]
    [:layers [:and [:vector layer-id] [:fn distinct-values?]
              [:fn #(= builtins (vec (take 2 %)))]]]
    [:layer-definitions [:map-of detail-id layer-definition]] [:faces [:map-of face-key layer-id]]]
   [:fn (fn [{:keys [layers layer-definitions faces]}]
          (let [ids (set layers)]
            (and (every? ids (keys layer-definitions)) (every? ids (vals faces)))))]])
(def stroke-entries
  [:vector [:map {:closed true} [:target :string] [:mesh-key :string] [:faces face-keys]]])

(def valid-vec3? (m/validator [:vector {:min 3 :max 3} finite-number]))
(def valid-matrix? (m/validator [:vector {:min 16 :max 16} finite-number]))
(def valid-face-keys? (m/validator face-keys))
