(ns shipyard.paint.strokes
  "Ordered transient stroke parts with one atomic, multi-instance commit on release."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [shipyard.catalog.db :as catalog]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.paint.faces :as faces]
            [shipyard.paint.transforms :as transforms]
            [shipyard.scheme.db :as schemes]
            [shipyard.wire :as wire]
            [shipyard.workspace.db :as workspace])
  (:import [java.nio.file Files]))

(defn parse-vector [text]
  (try
    (when (string? text)
      (let [forms (edn/read-string (str "[" text "]")) value (first forms)]
        (when (and (= 1 (count forms)) (vector? value)) value)))
    (catch Exception _ nil)))

(defn parse-faces [text]
  (let [value (parse-vector text)]
    (when (and (seq value) (every? faces/key? value)) value)))

(defn mesh-face-keys [{:keys [positions indices]}]
  (into []
        (map (fn [triangle]
               (faces/face-key
                (mapv (fn [corner]
                        (let [vertex (nth indices (+ (* triangle 3) corner))]
                          (mapv #(nth positions (+ (* vertex 3) %)) (range 3))))
                      (range 3)))))
        (range (quot (count indices) 3))))

(defn mesh-faces [mesh] (set (mesh-face-keys mesh)))

(defn ordered-face-keys! [{:keys [cache paint]} mesh-key]
  (let [file (cache/tier-file cache mesh-key 0) signature [mesh-key (fs/size file) :ordered]]
    (or (get @(:face-cache paint) signature)
        (let [keys (mesh-face-keys (wire/decode (Files/readAllBytes (fs/path file))))]
          (swap! (:face-cache paint) assoc signature keys)
          keys))))

(defn known-faces! [{:keys [cache paint] :as deps} mesh-key]
  (let [file (cache/tier-file cache mesh-key 0) signature [mesh-key (fs/size file)]]
    (or (get @(:face-cache paint) signature)
        (let [keys (set (ordered-face-keys! deps mesh-key))]
          (swap! (:face-cache paint) assoc signature keys)
          keys))))

(defn selected-face-keys
  "Resolve tier-0 ordinals, retaining geometric identity for duplicate triangles."
  [ordered {:keys [triangle-count indices]}]
  (when (and (= triangle-count (count ordered)) (seq indices)
             (every? #(and (integer? %) (<= 0 % (dec triangle-count))) indices))
    (mapv #(nth ordered %) indices)))

(defn apply-part
  "Incrementally extend validated layers; never persist a partial drag."
  [pending entries paint erase?]
  (reduce (fn [result {:keys [path part-id mesh-key keys]}]
            (if (:error result) (reduced result)
                (let [changed (faces/stroke (get-in result [:layers path]) part-id mesh-key keys paint erase?)]
                  (if (:error changed) changed
                      (assoc-in result [:layers path] (:layer changed))))))
          pending entries))

(defn history-change [details history operation path]
  (let [undo (vec (:undo history)) redo (vec (:redo history))]
    (case operation
      "undo" (if (and (seq undo) (= details (:after (peek undo))))
               {:details (:before (peek undo)) :history {:undo (pop undo) :redo (conj redo (peek undo))}}
               {:error :no-undo})
      "redo" (if (and (seq redo) (= details (:before (peek redo))))
               {:details (:after (peek redo)) :history {:undo (conj undo (peek redo)) :redo (pop redo)}}
               {:error :no-redo})
      "clear" {:details (dissoc details path)}
      {:error :invalid-operation})))

(defn commit-history [history before after]
  (if (= before after) history
      {:undo (vec (take-last 20 (conj (vec (:undo history)) {:before before :after after}))) :redo []}))

(defn- validate-entries! [{:keys [library] :as deps} targets entries]
  (if-not (and (vector? entries)
               (every? #(and (map? %) (= #{:target :mesh-key :faces} (set (keys %)))
                             (string? (:target %)) (string? (:mesh-key %))
                             (vector? (:faces %)) (seq (:faces %)) (every? faces/key? (:faces %))) entries))
    {:error :invalid-faces}
    (reduce (fn [result {:keys [target mesh-key faces]}]
              (let [selected (first (filter #(and (= target (:key %)) (contains? % :path)) targets))]
                (cond
                  (nil? selected) (reduced {:error :stale-stroke})
                  (or (not (index/fresh-source-file! library (:part-id selected)))
                      (not= mesh-key (index/mesh-key! library (:part-id selected)))) (reduced {:error :changed-source})
                  (not-every? (known-faces! deps mesh-key) faces) (reduced {:error :invalid-faces})
                  :else (update result :entries conj (assoc (select-keys selected [:path :part-id]) :mesh-key mesh-key :keys faces)))))
            {:entries []} entries)))

(defn- commit! [{:keys [workspace schemes]} record state details history]
  (let [saved (schemes/put! schemes (assoc record :scheme/details details) :update)]
    (when-not (:error saved)
      (workspace/update-workspace! workspace :paint assoc :brush-history
                                   (or history (commit-history (:brush-history state) (or (:scheme/details record) {}) details))))
    saved))

(defn stroke! [{:keys [paint workspace schemes catalog] {scheme-lock :lock} :schemes :as deps}
               {:strs [id target sequence mesh-key operation history stroke-id part final] :as params}]
  (try
    (let [draft (:draft @(:state paint)) state (workspace/workspace! workspace :paint)
          n (when (string? sequence) (parse-long sequence))
          operation (or history operation)]
      (if (or (nil? n) (<= n (or (:brush-sequence state) 0))
              (not= id (str (:scheme draft))) (not= target (:target state)))
        {:error :stale-stroke}
        (locking scheme-lock
          (workspace/update-workspace! workspace :paint assoc :brush-sequence n)
          (if-let [record (get-in (schemes/snapshot! schemes) [:schemes (:scheme draft)])]
            (let [targets (transforms/targets (catalog/snapshot! catalog) draft record)
                  selected (first (filter #(= target (:key %)) targets))
                  details (or (:scheme/details record) {})]
              (cond
                (nil? selected) {:error :stale-stroke}
                (= "cancel" operation)
                (do (when (= (some-> stroke-id (parse-uuid)) (get-in state [:brush-pending :id]))
                      (workspace/update-workspace! workspace :paint dissoc :brush-pending))
                    {:canceled true})
                (#{"undo" "redo" "clear"} operation)
                (if (or (:brush-pending state) (and (= "clear" operation) (not (contains? selected :path))))
                  {:error :stroke-in-progress}
                  (let [result (history-change details (:brush-history state) operation (:path selected))]
                    (if (:error result) result (commit! deps record state (:details result) (:history result)))))
                (not (#{"paint" "erase"} operation)) {:error :invalid-operation}
                :else
                (let [sid (if stroke-id (parse-uuid stroke-id) (random-uuid))
                      part (if stroke-id (some-> part (parse-long)) 0)
                      final? (or (nil? stroke-id) (= final "true"))
                      paint-value (transforms/parse-detail params)
                      pending (:brush-pending state)
                      raw (if (contains? params "entries") (parse-vector (get params "entries"))
                              [{:target target :mesh-key mesh-key :faces (parse-faces (get params "faces"))}])
                      checked (validate-entries! deps targets raw)
                      start (if (= 0 part)
                              {:id sid :next-part 0 :before details :layers details :operation operation
                               :paint paint-value :sources {} :target target :scheme (:scheme draft)} pending)
                      invalid (cond (nil? sid) :stale-stroke
                                    (and sid (= sid (:brush-committed-id state))) nil
                                    (or (nil? start) (not= sid (:id start)) (not= part (:next-part start))
                                        (not= target (:target start)) (not= (:scheme draft) (:scheme start))
                                        (not= operation (:operation start)) (not= paint-value (:paint start))) :stale-stroke
                                    (nil? paint-value) :invalid-material
                                    (:error checked) (:error checked))]
                  (cond
                    (and sid (= sid (:brush-committed-id state))) {:scheme record}
                    invalid (do (workspace/update-workspace! workspace :paint dissoc :brush-pending) {:error invalid})
                    :else
                    (let [result (apply-part start (:entries checked) paint-value (= operation "erase"))
                          result (update result :sources into (map (juxt :part-id :mesh-key) (:entries checked)))
                          sources-current? (fn [] (every? (fn [[part-id key]]
                                                            (and (index/fresh-source-file! (:library deps) part-id)
                                                                 (= key (index/mesh-key! (:library deps) part-id)))) (:sources result)))]
                      (cond
                        (:error result) (do (workspace/update-workspace! workspace :paint dissoc :brush-pending) result)
                        (not final?) (do (workspace/update-workspace! workspace :paint assoc :brush-pending (update result :next-part inc))
                                         {:buffered true})
                        :else
                        (do (workspace/update-workspace! workspace :paint dissoc :brush-pending)
                            (cond (not= details (:before start)) {:error :stale-stroke}
                                  (not (sources-current?)) {:error :changed-source}
                                  :else (let [saved (commit! deps record state (:layers result) nil)]
                                          (when-not (:error saved)
                                            (workspace/update-workspace! workspace :paint assoc :brush-committed-id sid))
                                          saved)))))))))
            {:error :missing-scheme}))))
    (catch Exception _
      (workspace/update-workspace! workspace :paint dissoc :brush-pending)
      {:error :brush-unavailable})))
