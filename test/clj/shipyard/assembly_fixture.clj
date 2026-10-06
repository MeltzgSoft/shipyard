(ns shipyard.assembly-fixture
  "Synthetic authored Cruiser hierarchy shared by HTTP and browser proofs."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [shipyard.catalog.db :as catalog]
            [shipyard.fixtures :as fixtures]
            [shipyard.system :as system]))

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
          stl (fs/file dir (if (= role :supported) "supported.stl" "unsupported.stl"))]
      (fs/create-dirs dir)
      (with-open [out (io/output-stream stl)]
        (.write out ^bytes (fixtures/->binary-stl
                            (if (= role :hull)
                              (mapv (fn [triangle] (mapv (fn [[x y z]] [(* 3 x) (* 3 y) z]) triangle))
                                    (fixtures/cube 4.0))
                              (fixtures/cube 1.0)))))))
  root)

(defn authored []
  (into {} (for [[role id] ids :when (not= role :hint)]
             [id {:part-role (case role :prow-alt :prow :weapon-alt :weapon role)
                  :mounts (case role :hull hull-mounts
                                (:weapon :weapon-alt) weapon-mounts [plug])}])))

(defn author! [cat]
  (doseq [[id value] (authored)
          :when (catalog/part (catalog/snapshot! cat) id)]
    (catalog/save-authoring! cat id value)))

(defn stop! [{:keys [temp system]}]
  (system/stop! system)
  (fs/delete-tree temp))

(defn start!
  ([] (start! false))
  ([server?] (start! server? library!))
  ([server? build-library!] (start! server? build-library! author!))
  ([server? build-library! author-catalog!] (start! server? build-library! author-catalog! {}))
  ([server? build-library! author-catalog! overrides]
   (let [temp (fs/create-temp-dir {:prefix "shipyard-assembly-"})
         started (volatile! nil)]
     (try
       (let [root (build-library! (fs/path temp "library"))
             config-dir (str (fs/path temp "config"))
             cfg (system/read-config! "shipyard/systems/assembly.edn"
                                      {:shipyard.library/index {:root (str root) :config-dir config-dir}
                                       :shipyard.mesh/cache {:cache-home (str (fs/path temp "cache"))}
                                       :shipyard.store/db {:data-home (str (fs/path temp "data"))}
                                       :shipyard.http/routes {:config-dir config-dir}})
             cfg (cond-> cfg
                   server? (system/deep-merge (system/read-config! "shipyard/systems/server.edn")))
             cfg (system/deep-merge cfg overrides)
             cfg (cond-> cfg (not server?) (dissoc :shipyard.http/server))
             sys (system/start! cfg)]
         (vreset! started sys)
         (author-catalog! (:shipyard.catalog/db sys))
         {:temp temp :root root :system sys :handler (:shipyard.http/routes sys)})
       (catch Throwable error
         (try
           ;; A partial init is normally halted by system/start!. If that
           ;; cleanup failed, retry it before deleting any working files.
           (stop! {:temp temp :system (or @started (:system (ex-data error)))})
           (catch Throwable cleanup-error
             (.addSuppressed error cleanup-error)))
         (throw error))))))
