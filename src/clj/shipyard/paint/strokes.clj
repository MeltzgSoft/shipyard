(ns shipyard.paint.strokes
  "Durable brush operations with bounded, workspace-owned undo history."
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

(defn parse-faces [text]
  (try
    (when (and (string? text) (<= (count text) 90000))
      (let [forms (edn/read-string (str "[" text "]")) value (first forms)]
        (when (and (= 1 (count forms)) (vector? value)
                   (<= 1 (count value) faces/max-stroke-faces) (every? faces/key? value)) value)))
    (catch Exception _ nil)))

(defn mesh-faces [{:keys [positions indices]}]
  (into #{}
        (map (fn [triangle]
               (faces/face-key
                (mapv (fn [corner]
                        (let [vertex (nth indices (+ (* triangle 3) corner))]
                          (mapv #(nth positions (+ (* vertex 3) %)) (range 3))))
                      (range 3)))))
        (range (quot (count indices) 3))))

(defn- known-faces! [{:keys [cache paint]} mesh-key]
  (let [file (cache/tier-file cache mesh-key 0)
        ;; Cache LRU touches do not alter source-space identity.
        signature [mesh-key (fs/size file)]
        saved @(:face-cache paint)]
    (if (= signature (:signature saved))
      (:keys saved)
      (let [keys (mesh-faces (wire/decode (Files/readAllBytes (fs/path file))))]
        (reset! (:face-cache paint) {:signature signature :keys keys})
        keys))))

(defn change-layer [layer history operation part-id mesh-key keys rgb]
  (let [undo (vec (:undo history)) redo (vec (:redo history))]
    (case operation
      "undo" (if (and (seq undo) (= layer (:after (peek undo))))
               {:layer (:before (peek undo)) :history {:undo (pop undo) :redo (conj redo (peek undo))}}
               {:error :no-undo})
      "redo" (if (and (seq redo) (= layer (:before (peek redo))))
               {:layer (:after (peek redo)) :history {:undo (conj undo (peek redo)) :redo (pop redo)}}
               {:error :no-redo})
      (let [result (if (= operation "clear") {:layer nil}
                       (if (#{"paint" "erase"} operation)
                         (faces/stroke layer part-id mesh-key keys rgb (= operation "erase"))
                         {:error :invalid-operation}))]
        (cond (:error result) result
              (= layer (:layer result)) (assoc result :history history)
              :else (assoc result :history {:undo (vec (take-last 20 (conj undo {:before layer :after (:layer result)}))) :redo []}))))))

(defn stroke! [{:keys [paint workspace schemes library catalog] {scheme-state :state} :schemes :as deps}
               {:strs [id target sequence mesh-key operation history] :as params}]
  (try
    (let [draft (:draft @(:state paint)) state (workspace/workspace! workspace :paint)
          selected (first (filter #(= target (:key %)) (transforms/targets (catalog/snapshot! catalog) draft)))
          n (when (string? sequence) (parse-long sequence))
          fresh (when (:part-id selected) (index/fresh-source-file! library (:part-id selected)))
          keys (parse-faces (get params "faces"))
          paint-value (transforms/parse-detail params)
          operation (or history operation)]
      (cond
        (or (nil? n) (<= n (or (:brush-sequence state) 0))
            (not= id (str (:scheme draft))) (not= target (:target state))
            (not (contains? selected :path))) {:error :stale-stroke}
        (or (not fresh) (not= mesh-key (index/mesh-key! library (:part-id selected)))) {:error :changed-source}
        (and (#{"paint" "erase"} operation)
             (or (nil? keys) (not-every? (known-faces! deps mesh-key) keys))) {:error :invalid-faces}
        :else
        (locking scheme-state
          (if-let [record (get-in (schemes/snapshot! schemes) [:schemes (:scheme draft)])]
            (let [path (:path selected) layer (get-in record [:scheme/details path])
                  result (change-layer layer (:brush-history state) operation (:part-id selected) mesh-key keys paint-value)
                  value (if (:layer result)
                          (assoc-in record [:scheme/details path] (:layer result))
                          (update record :scheme/details #(dissoc (or % {}) path)))
                  saved (cond (:error result) result
                              (> (reduce + 0 (map #(count (:faces %)) (vals (:scheme/details value)))) faces/max-painted-faces)
                              {:error :paint-limit}
                              :else (schemes/put! schemes value :update))]
              (workspace/update-workspace! workspace :paint assoc :brush-sequence n)
              (when-not (:error saved)
                (workspace/update-workspace! workspace :paint assoc :brush-history (:history result)))
              saved)
            {:error :missing-scheme}))))
    (catch Exception _ {:error :brush-unavailable})))
