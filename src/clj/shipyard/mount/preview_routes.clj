(ns shipyard.mount.preview-routes
  (:require [shipyard.catalog.db :as catalog]
            [shipyard.math :as math]
            [shipyard.mount.preview :as preview]
            [shipyard.mount.preview-db :as drafts]
            [shipyard.mount.preview-wire :as wire]
            [shipyard.mount.wizard :as wizard]
            [shipyard.preparation :as preparation]
            [shipyard.preparation.transforms :as transforms]))

(def finite [:and number? [:fn math/finite-number?]])
(def positive [:and finite [:> 0]])
(def vec3 [:vector {:min 3 :max 3} finite])
(def indices [:vector {:min 1 :max 4096} [:int {:min 0 :max 2147483647}]])
(def cut-schema
  [:or [:map {:closed true} [:kind [:= :pit]] [:depth positive] [:diameter positive]]
   [:map {:closed true} [:kind [:= :recess]] [:depth positive] [:border [:and finite [:>= 0]]]]])
(def draft-schema
  [:map {:closed true}
   [:part-id string?] [:mesh-key [:re #"[0-9a-f]{64}"]]
   [:owner [:string {:min 1 :max 80}]] [:sequence [:int {:min 0 :max 2147483647}]]
   [:frame [:map {:closed true} [:mount/pos vec3] [:mount/axis vec3] [:mount/roll vec3]]]
   [:indices {:optional true} indices] [:border-indices {:optional true} indices]
   [:mount-id {:optional true} string?]
   [:cut {:optional true} cut-schema]
   [:capacity [:int {:min 1 :max 256}]] [:direction [:enum :horizontal :vertical]]
   [:mirror {:optional true} [:map {:closed true} [:plane [:enum :x :y :z]] [:offset finite]]]])

(defn- response [entry]
  {:status 200 :headers {"content-type" "application/edn; charset=utf-8" "cache-control" "no-store"}
   :body (pr-str (transforms/envelope entry))})

(defn- build-draft! [service mesh-key value opts]
  (let [bytes (wire/encode (preview/draft (preparation/read-mesh! service mesh-key 0) value opts))]
    {:value {:bytes bytes :content-type "application/octet-stream"}
     :size (+ (alength ^bytes bytes) (* 32 (+ (count (:indices value)) (count (:border-indices value)))))}))

(defn- prepare-draft! [service mesh-key value opts current?!]
  (if (current?!)
    (preparation/cached-source! service
                                [:mount-preview wire/version mesh-key (dissoc value :owner :sequence :part-id :mount-id) opts]
                                build-draft! [service mesh-key value opts])
    ;; Publication rechecks the same guard and discards this obsolete resource.
    {:bytes (byte-array 0)}))

(defn draft! [{:keys [preparation catalog cache mount-previews]} {{:keys [body]} :parameters}]
  (let [{:keys [part-id mesh-key mount-id owner sequence] :as value} body
        part (:part (catalog/part-context! catalog part-id))
        saved (when mount-id (wizard/mount-by-id (:part/mounts part) (keyword mount-id)))
        value (cond-> (assoc value :part-orientation (:part/orientation part))
                (and saved (not (:indices value)))
                (assoc-in [:frame :mount/outline] (:mount/outline saved)))
        current?! #(and (drafts/current?! mount-previews owner sequence)
                        (= (:part/revision part) (:part/revision (catalog/summary! catalog part-id))))]
    (drafts/advance! mount-previews owner sequence false)
    (response (preparation/request! preparation
                                    {:key [:mount-preview wire/version mesh-key 0 (:part/revision part) owner sequence value]
                                     :part-id part-id :mesh-key mesh-key
                                     :retained-bytes (* 64 (+ (count (:indices value)) (count (:border-indices value))))
                                     :valid?! current?!
                                     :run! prepare-draft! :args [preparation mesh-key value
                                                                 (select-keys cache [:facet-angle-deg :facet-plane-epsilon-mm]) current?!]}))))

(defn cancel! [{:keys [mount-previews]} {{:keys [body]} :parameters}]
  (drafts/advance! mount-previews (:owner body) (:sequence body) true)
  {:status 204 :body ""})

(defn- prepare-saved! [mounts mesh-key]
  {:bytes (wire/encode {:cuts (vec (mapcat preview/lines
                                           (filter #(= mesh-key (get-in % [:mount/cut :mesh-key])) mounts)))})
   :content-type "application/octet-stream"})

(defn saved! [{:keys [preparation catalog]} {{:keys [query]} :parameters}]
  (let [{:keys [part-id mesh-key]} query
        part (:part (catalog/part-context! catalog part-id))]
    (response (preparation/request! preparation
                                    {:key [:saved-mount-preview wire/version mesh-key (:part/revision part) (:part/mounts part)]
                                     :part-id part-id :mesh-key mesh-key :run! prepare-saved!
                                     :valid?! #(= (:part/revision part) (:part/revision (catalog/summary! catalog part-id)))
                                     :args [(:part/mounts part) mesh-key]}))))

(defn routes [deps]
  (when (:preparation deps)
    [["/mounts/preview" {:post {:handler (partial draft! deps) :parameters {:body draft-schema}}}]
     ["/mounts/preview/cancel" {:post {:handler (partial cancel! deps)
                                       :parameters {:body [:map [:owner [:string {:min 1 :max 80}]]
                                                           [:sequence [:int {:min 0 :max 2147483647}]]]}}}]
     ["/mounts/previews" {:get {:handler (partial saved! deps)
                                :parameters {:query [:map [:part-id string?] [:mesh-key [:re #"[0-9a-f]{64}"]]]}}}]]))
