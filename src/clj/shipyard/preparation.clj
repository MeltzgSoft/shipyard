(ns shipyard.preparation
  "On-demand source-bound derived resources on the application's bounded workers."
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [shipyard.jobs :as jobs]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.preparation.transforms :as transforms]
            [shipyard.wire :as wire])
  (:import [java.nio.file Files]
           [java.nio.charset StandardCharsets]))

(defn- current?! [{:keys [library]} {:keys [part-id mesh-key expected valid?!]}]
  (and (= mesh-key (index/mesh-key! library part-id))
       (index/current-source?! library part-id expected)
       (or (nil? valid?!) (valid?!))))

(defn- prune! [{:keys [state library]}]
  (swap! state update :entries
         #(into {} (filter (fn [[_ entry]] (current?! {:library library} entry))) %)))

(defn status!
  "Look up a resource; recheck source freshness at delivery, including ready hits."
  [{:keys [state library] :as service} resource]
  (let [library-lock (:state library)]
    (locking library-lock
      (locking state
        (prune! service)
        (when-let [[key entry] (some #(when (= resource (:resource (val %))) %) (:entries @state))]
          (let [tick (:tick (swap! state update :tick inc))]
            (swap! state assoc-in [:entries key :access] tick)
            entry))))))

(defn forget!
  "Explicit retry forgets failed completed resources, never duplicates running work."
  [{:keys [state]} key]
  (locking state
    (swap! state update :entries
           #(into {} (remove (fn [[k entry]] (and (= key (:key entry)) (= :failed (:state entry)) (some? k)))) %))))

(defn cached-source!
  "Worker-only bounded memo for immutable source analysis. build! returns
  {:value immutable-data :size estimated-resident-bytes}; never wait on jobs here."
  [{:keys [meshes mesh-cap-bytes]} key build! args]
  (locking meshes
    (let [tick (:tick (swap! meshes update :tick inc))]
      (if-let [entry (get-in @meshes [:entries key])]
        (do (swap! meshes assoc-in [:entries key :access] tick) (:value entry))
        (let [{:keys [value size]} (apply build! args)]
          (swap! meshes update :entries #(transforms/trim (assoc % key {:state :ready :value value :size (or size 0) :access tick}) mesh-cap-bytes 16))
          value)))))

(defn- decode-mesh! [cache mesh-key tier]
  (let [bytes (Files/readAllBytes (fs/path (cache/tier-file cache mesh-key tier)))
        mesh (wire/decode bytes)]
    {:value mesh :size (* 4 (+ (count (:positions mesh)) (count (:normals mesh)) (count (:indices mesh))))}))

(defn read-mesh!
  "Worker-only shared decoded geometry, bounded independently of derived resources."
  [{:keys [cache] :as service} mesh-key tier]
  (cached-source! service [:mesh mesh-key tier] decode-mesh! [cache mesh-key tier]))

(defn- execute! [{:keys [state library cap-bytes max-entries] :as service} key mine run! args]
  (let [library-lock (:state library)
        result (try
                 (let [{:keys [value bytes content-type] :as result} (apply run! args)
                       bytes (or bytes (.getBytes (pr-str value) StandardCharsets/UTF_8))]
                   (merge result {:state :ready :value value :bytes bytes :size (+ (alength ^bytes bytes) (long (or (:size result) 0)))
                                  :content-type (or content-type "application/edn; charset=utf-8")}))
                 (catch Throwable error
                   (log/warn error "Derived preparation failed" (:key mine))
                   {:state :failed :message "Preparation failed. Retry to try again."}))]
    (locking library-lock
      (locking state
        (when (= (:resource mine) (get-in @state [:entries key :resource]))
          (swap! state update :entries
                 #(transforms/trim (if (current?! service mine) (assoc % key (merge mine result)) (dissoc % key)) cap-bytes max-entries)))))))

(defn request!
  "Deduplicate source-bound descriptors. Producers run only on shared workers and
  return {:value domain-value :bytes optional-byte-array :content-type optional}.
  Keys include feature/version/mesh/tier/options and all relevant authoring revisions."
  [{:keys [state library scope] :as service} {:keys [key part-id mesh-key run! args valid?!]}]
  (let [library-lock (:state library)]
    (locking library-lock
      (locking state
        (prune! service)
        (let [source (index/fresh-source-file! library part-id)
              expected (assoc (index/part-state! library part-id) :source source)
              cache-key [(:root expected) (select-keys (:entry expected) [:mtime :size]) key]]
          (if-not (and source (= mesh-key (index/mesh-key! library part-id)))
            {:state :failed :message "The source changed. Rescan and reopen this part."}
            (if-let [entry (get-in @state [:entries cache-key])]
              (do (swap! state update :tick inc)
                  (swap! state assoc-in [:entries cache-key :access] (:tick @state))
                  entry)
              (let [mine (hash-map :state :running :resource (str (random-uuid)) :part-id part-id
                                   :mesh-key mesh-key :expected expected :valid?! valid?! :key key :access (:tick @state))]
                (swap! state assoc-in [:entries cache-key] mine)
                (if (:accepted? (jobs/submit-batch! scope [{:key cache-key :run! execute! :args [service cache-key mine run! args]}]))
                  mine
                  (do (swap! state update :entries dissoc cache-key)
                      {:state :overloaded :message "Preparation capacity is busy. Retry shortly."}))))))))))

(defn close! [{:keys [scope state meshes]}]
  (jobs/close! scope)
  (reset! state {:entries {} :tick 0})
  (reset! meshes {:entries {} :tick 0}))

(defmethod ig/init-key :shipyard.preparation/service [_ {:keys [workers] :as options}]
  (merge {:cap-bytes 134217728 :mesh-cap-bytes 67108864 :max-entries 128} options
         {:scope (jobs/scope! workers) :state (atom {:entries {} :tick 0}) :meshes (atom {:entries {} :tick 0})}))

(defmethod ig/halt-key! :shipyard.preparation/service [_ service] (close! service))
