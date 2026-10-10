(ns shipyard.regions.mirror-preparation
  "Automatic region plane estimation and precise oriented bounds on shared workers."
  (:require [clojure.edn :as edn]
            [shipyard.catalog.db :as catalog]
            [shipyard.part.orientation :as orientation]
            [shipyard.preparation :as preparation]
            [shipyard.regions.center :as center]
            [shipyard.workspace.db :as workspace]))

(defn projection
  "Preserve source translation, canonical orientation and the outer-surface median."
  [{:keys [positions indices]} part-orientation axis]
  (let [points (mapv #(orientation/rotate-vector part-orientation (vec %)) (partition 3 positions))
        bounds (reduce (fn [bounds point]
                         (if-let [[lo hi] bounds] [(mapv min lo point) (mapv max hi point)] [point point])) nil points)
        bounds (or bounds [[0 0 0] [0 0 0]])
        triangles (map (fn [[a b c]] [(nth points a) (nth points b) (nth points c)]) (partition 3 indices))]
    {:bounds bounds :offset (center/estimate triangles bounds axis)}))

(defn- prepare! [service mesh-key part-orientation axis]
  {:value (projection (preparation/read-mesh! service mesh-key 0) part-orientation axis)})

(defn parse-orientation [raw]
  (try (orientation/normalize-quaternion (edn/read-string raw)) (catch Exception _ nil)))

(defn request! [{:keys [preparation workspace catalog]} {:keys [params]}]
  (let [{:strs [part-id mesh-key axis quaternion]} params
        axis (keyword axis) q (parse-orientation quaternion)
        part (:part (catalog/part-context! catalog part-id))]
    (if-not (and (= part-id (:selection (workspace/workspace! workspace :browse)))
                 (#{:x :y :z} axis) q
                 (every? #(< (abs %) 1e-9) (map - q (orientation/orientation-of (:part/orientation part)))))
      {:status 409 :headers {"content-type" "application/edn"}
       :body (pr-str {:state :failed :message "Part, orientation or axis changed. Reopen this part."})}
      (let [result (preparation/request! preparation {:key [:region-mirror 1 mesh-key 0 q axis]
                                                      :part-id part-id :mesh-key mesh-key
                                                      :run! prepare! :args [preparation mesh-key q axis]})]
        {:status 200 :headers {"content-type" "application/edn" "cache-control" "no-store"}
         :body (pr-str (select-keys result [:state :resource :message]))}))))
