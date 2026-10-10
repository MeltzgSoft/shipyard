(ns shipyard.e2e.projection-profile-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.paint-preparation-test :as profile]
            [shipyard.e2e.named-ship-test :as named]
            [shipyard.fixtures :as meshes]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as loadouts]
            [shipyard.paint.projection-job :as projection]
            [shipyard.preparation :as preparation]
            [shipyard.scheme.db :as schemes]
            [shipyard.scheme.material :as material]
            [shipyard.ship.db :as ships]))

(defn- detailed-library! [root]
  (fixture/library! root)
  (with-open [output (io/output-stream (fs/file root (:weapon fixture/ids) "unsupported.stl"))]
    (.write output ^bytes (meshes/->binary-stl (meshes/uv-sphere 1.0 96 192))))
  root)

(deftest dense-numeric-masks-share-a-resource-across-thirteen-instances
  (s/assert-bundle!)
  (let [started (fixture/start! true detailed-library!) sys (:system started) driver (profile/profile-driver!)
        class-id (random-uuid) scheme-id (random-uuid) ship-id (random-uuid)
        detail (lf/detail-layer! sys (:weapon fixture/ids) (assoc material/neutral :base [0 1 0]))
        keys (projection/ordered-keys (preparation/read-mesh! (:shipyard.preparation/service sys) (:mesh-key detail) 0))
        details (update detail :faces select-keys (take-nth 2 keys))
        weapon-slots (into {} (keep (fn [[slot id]] (when (= id (:weapon fixture/ids)) [slot details]))) lf/assignments)
        prepared #(and (= 13 (count (get-in (s/stats driver) [:assembly :slots])))
                       (every? (fn [slot] (and (not (:paint-preparing slot))
                                               (or (not= (:part-id slot) (:weapon fixture/ids)) (:appearance-prepared slot))))
                               (get-in (s/stats driver) [:assembly :slots])))]
    (try
      (catalog/save-regions! (:shipyard.catalog/db sys) (:weapon fixture/ids)
                             {:version 2 :mesh-key (:mesh-key detail) :revision 7 :layers ["Primary" "Secondary"]
                              :layer-definitions {} :faces (zipmap keys (repeat "Secondary"))})
      (loadouts/put! (:shipyard.loadout/db sys) {:loadout/id class-id :loadout/name "Numeric cruiser"
                                                 :loadout/hull (:hull fixture/ids) :loadout/slots lf/assignments} :create)
      (schemes/put! (:shipyard.scheme/db sys) {:scheme/id scheme-id :scheme/name "Numeric palette"
                                               :scheme/layers {"Primary" material/neutral "Secondary" (assoc material/neutral :base [1 0 0])}} :create)
      (ships/put! (:shipyard.ship/db sys) {:ship/id ship-id :ship/name "Numeric vessel" :ship/class class-id
                                           :ship/scheme scheme-id :ship/paint {:paint/details weapon-slots}} :create)
      (s/go! driver (s/base-url sys))
      (s/js driver "() => {window.numericTicks=0;window.numericLast=performance.now();window.numericMaxGap=0;setInterval(()=>{const t=performance.now();window.numericMaxGap=Math.max(window.numericMaxGap,t-window.numericLast);window.numericLast=t;window.numericTicks++;},10);}")
      (s/open-class! driver "Numeric cruiser")
      (s/open-named-ship! driver ship-id)
      (is (s/wait-until prepared 60000))
      (let [slots (filter #(= (:weapon fixture/ids) (:part-id %)) (get-in (s/stats driver) [:assembly :slots]))
            resources (set (map :appearance-resource slots))]
        (is (= 4 (count slots)))
        (is (= 1 (count resources)) "Repeated transformed placements share the identical numeric resource")
        (is (every? #(and (= 36480 (:projection-layers %)) (= 36480 (:projection-details %)) (:vertex-colors %)) slots))
        (is (every? #(= #{[1 0 0] [0 1 0]} (set (:projection-sample %))) slots))
        (let [uuids (set (map :uuid slots)) ticks (s/js driver "() => window.numericTicks")]
          (named/tab! driver "Schemes")
          (s/select-option! driver "#scheme-select select" "Numeric palette")
          (s/wait-visible! driver "#scheme-material")
          (named/scheme-layer! driver "Secondary")
          (s/input! driver "#scheme-material input[name=base]" "#0000ff" "input")
          (is (s/wait-until #(and (prepared)
                                  (every? (fn [slot] (= #{[0 0 1] [0 1 0]} (set (:projection-sample slot))))
                                          (filter (fn [slot] (= (:weapon fixture/ids) (:part-id slot)))
                                                  (get-in (s/stats driver) [:assembly :slots])))) 60000))
          (let [warm (filter #(= (:weapon fixture/ids) (:part-id %)) (get-in (s/stats driver) [:assembly :slots]))]
            (is (= uuids (set (map :uuid warm))))
            (is (= resources (set (map :appearance-resource warm))))
            (is (every? #(= #{[0 0 1] [0 1 0]} (set (:projection-sample %))) warm))
            (is (> (s/js driver "() => window.numericTicks") ticks))
            (let [report {:browser (or (System/getProperty "shipyard.paint.profile.browser") "chromium")
                          :instances 13 :painted-instances 4 :triangles-per-painted-instance 36480
                          :numeric-resource-count (count resources)
                          :cold (mapv :paint-preparation slots) :warm (mapv :paint-preparation warm)
                          :event-loop-max-gap-ms (s/js driver "() => window.numericMaxGap")}]
              (spit "/tmp/shipyard-projection-profile.edn" (pr-str report))
              (println "Numeric projection profile:" (pr-str report))))))
      (finally (s/quit! driver) (fixture/stop! started)))))
