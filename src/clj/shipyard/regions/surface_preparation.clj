(ns shipyard.regions.surface-preparation
  "Worker-only source topology and compact connected-surface partitions."
  (:require [shipyard.preparation :as preparation]
            [shipyard.regions.surfaces :as surfaces]
            [shipyard.workspace.db :as workspace])
  (:import [java.nio ByteBuffer ByteOrder]))

(defn mesh-triangles [{:keys [positions indices]}]
  (mapv (fn [triangle]
          (mapv (fn [corner]
                  (let [vertex (nth indices (+ (* triangle 3) corner))]
                    (mapv #(nth positions (+ (* vertex 3) %)) (range 3)))) (range 3)))
        (range (quot (count indices) 3))))

(defn encode-components [{:keys [ids offsets members]}]
  (let [values (concat [(count ids) (dec (count offsets))] ids offsets members)
        buffer (.order (ByteBuffer/allocate (* 4 (+ 2 (count ids) (count offsets) (count members)))) ByteOrder/LITTLE_ENDIAN)]
    (doseq [value values] (.putInt buffer (int value)))
    (.array buffer)))

(defn- topology! [service mesh-key]
  (let [mesh (preparation/read-mesh! service mesh-key 0)
        triangles (mesh-triangles mesh)]
    {:value (surfaces/topology triangles) :size (* 512 (count triangles))}))

(defn- partition! [service mesh-key angle]
  (let [topology (preparation/cached-source! service [:region-topology 1 mesh-key 0] topology! [service mesh-key])
        bytes (encode-components (surfaces/partition-components topology angle))]
    {:value nil :bytes bytes :content-type "application/octet-stream"}))

(defn request! [{:keys [preparation workspace]} {:keys [params]}]
  (let [{:strs [part-id mesh-key angle]} params
        angle (some-> angle parse-double)]
    (if-not (and (= part-id (:selection (workspace/workspace! workspace :browse)))
                 (some? angle) (<= 0 angle 90))
      {:status 409 :body (pr-str {:state :failed :message "Part or tolerance changed. Reopen this part."})}
      (let [result (preparation/request! preparation {:key [:region-surfaces 1 mesh-key 0 angle]
                                                      :part-id part-id :mesh-key mesh-key
                                                      :run! partition! :args [preparation mesh-key angle]})]
        {:status 200 :headers {"content-type" "application/edn" "cache-control" "no-store"}
         :body (pr-str (select-keys result [:state :resource :message]))}))))
