(ns shipyard.assembly-fixture
  "Synthetic authored Cruiser hierarchy shared by HTTP and browser proofs."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [shipyard.catalog.sidecar :as sidecar]
            [shipyard.fixtures :as fixtures]
            [shipyard.system :as system])
  (:import [java.util.concurrent ExecutorService TimeUnit]))

(def frame {:mount/pos [0.0 0.0 0.0] :mount/axis [0.0 0.0 1.0] :mount/roll [1.0 0.0 0.0]})
(def plug (assoc frame :mount/id :plug :mount/kind :plug :mount/origin :picked
                 :mount/pos [0.0 0.0 -0.5] :mount/axis [0.0 0.0 -1.0]))
(def ids (into {} (map (fn [role] [role (str "Synthetic Navy/Cruiser/" (name role))]))
               [:hull :prow :prow-alt :bridge :antenna :weapon :weapon-alt :turret :hint :supported]))

(def hull-mounts
  [(merge frame {:mount/id :weapon :mount/kind :socket :mount/accepts #{:weapon}
                 :mount/pos [0.0 0.0 2.0] :mount/capacity 2
                 :mount/split {:direction :vertical :bounds [[-6.0 -2.0] [6.0 2.0]]}})
   (merge frame {:mount/id :mirrored-weapon :mount/kind :socket :mount/accepts #{:weapon}
                 :mount/pos [0.0 0.0 -2.0] :mount/axis [0.0 0.0 -1.0]
                 :mount/origin :mirrored :mount/capacity 2
                 :mount/split {:direction :vertical :bounds [[-6.0 -2.0] [6.0 2.0]]}})
   (merge frame {:mount/id :prow :mount/kind :socket :mount/accepts #{:prow} :mount/pos [0.0 4.0 2.0]})
   (merge frame {:mount/id :bridge :mount/kind :socket :mount/accepts #{:bridge} :mount/pos [0.0 -4.0 2.0]})
   (merge frame {:mount/id :antenna :mount/kind :socket :mount/accepts #{:antenna}
                 :mount/pos [0.0 0.0 -2.0] :mount/axis [0.0 0.0 -1.0] :mount/capacity 2
                 :mount/split {:direction :horizontal :bounds [[-1.0 -3.0] [1.0 3.0]]}})])

(def weapon-mounts
  [plug (merge frame {:mount/id :turret :mount/kind :socket :mount/accepts #{:turret}
                      :mount/pos [0.0 0.0 0.5]})])

(defn library! [root]
  (doseq [[role id] ids]
    (let [dir (fs/file root id)
          stl (fs/file dir (if (= role :supported) "supported.stl" "unsupported.stl"))
          authored-role (case role :prow-alt :prow :weapon-alt :weapon role)]
      (fs/create-dirs dir)
      (with-open [out (io/output-stream stl)]
        (.write out ^bytes (fixtures/->binary-stl
                            (if (= role :hull)
                              (mapv (fn [triangle] (mapv (fn [[x y z]] [(* 3 x) (* 3 y) z]) triangle))
                                    (fixtures/cube 4.0))
                              (fixtures/cube 1.0)))))
      (when-not (= role :hint)
        (sidecar/write-sidecar! (str root) id
                                {:part/role authored-role
                                 :mounts (case role :hull hull-mounts
                                               (:weapon :weapon-alt) weapon-mounts [plug])}))))
  root)

(defn start!
  ([] (start! false))
  ([server?] (start! server? library!))
  ([server? build-library!]
   (let [temp (fs/create-temp-dir {:prefix "shipyard-assembly-"})
         root (build-library! (fs/path temp "library"))
         cfg (-> (system/load-config! {:profile :test :config-dir (str (fs/path temp "config")) :env {}})
                 (assoc-in [:shipyard.library/index :root] (str root))
                 (assoc-in [:shipyard.library/index :cache-home] (str (fs/path temp "cache")))
                 (assoc-in [:shipyard.mesh/cache :cache-home] (str (fs/path temp "cache")))
                 (assoc-in [:shipyard.store/db :data-home] (str (fs/path temp "data")))
                 (assoc-in [:shipyard.http/routes :config-dir] (str (fs/path temp "config")))
                 (assoc-in [:shipyard.http/server :port] 0))
         cfg (cond-> cfg (not server?) (dissoc :shipyard.http/server))
         started (system/start! cfg)]
     {:temp temp :root root :system started :handler (:shipyard.http/routes started)})))

(defn stop! [{:keys [temp system]}]
  (ig/halt! system)
  (when-let [^ExecutorService pool (get-in system [:shipyard.http/jobs :pool])]
    (.awaitTermination pool 30 TimeUnit/SECONDS))
  (fs/delete-tree temp))
