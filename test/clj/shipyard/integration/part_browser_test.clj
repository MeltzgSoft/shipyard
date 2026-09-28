(ns shipyard.integration.part-browser-test
  (:require [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]))

(deftest bulk-metadata-validation-and-atomicity
  (let [started (fixture/start!) handler (:handler started)
        a (:prow fixture/ids) b (:bridge fixture/ids)
        cat (:shipyard.catalog/db (:system started))
        state (get-in started [:system :shipyard.workspace/db :state])
        initial (catalog/snapshot! cat)
        post! #(handler (mock/request :post "/parts/metadata" %))]
    (try
      (swap! state assoc-in [:workspaces :browse :bulk-selection] (pr-str [a b]))
      (is (= 400 (:status (post! {"field" "orientation" "operation" "set" "value" "45"}))))
      (is (= 422 (:status (post! {"field" "name" "operation" "set" "value" " "}))))
      (is (= 422 (:status (post! {"field" "role" "operation" "set" "value" "bogus"}))))
      (is (= initial (catalog/snapshot! cat)))
      (swap! state assoc-in [:workspaces :browse :bulk-selection] (pr-str [a "missing"]))
      (is (= 422 (:status (post! {"field" "bundle" "operation" "set" "value" "New"}))))
      (is (= initial (catalog/snapshot! cat)))
      (swap! state assoc-in [:workspaces :browse :bulk-selection] (pr-str [a b]))
      (is (= 200 (:status (post! {"field" "class" "operation" "set" "value" "New Class"}))))
      (doseq [id [a b]]
        (let [before (catalog/part initial id)
              after (catalog/part (catalog/snapshot! cat) id)]
          (is (= "New Class" (:part/class after)))
          (is (= (select-keys before [:part/id :part/uid :part/mounts :part/paint-regions :part/orientation])
                 (select-keys after [:part/id :part/uid :part/mounts :part/paint-regions :part/orientation])))))
      (finally (fixture/stop! started)))))

(deftest row-summaries-exclude-face-payloads
  (let [started (fixture/start!) cat (get-in started [:system :shipyard.catalog/db])
        id (:weapon fixture/ids) read! #(catalog/part (catalog/listing! cat) id)
        regions {:version 2 :layer-definitions {} :mesh-key (apply str (repeat 64 "a")) :revision 0 :layers ["Primary" "Secondary"]
                 :faces (zipmap (map #(format "%072x" %) (range 10000)) (repeat "Secondary"))}]
    (try
      (is (= {:plugs 1 :sockets {#{:turret} 1}} (:part/mount-summary (read!))))
      (is (false? (:part/has-regions? (read!))))
      (catalog/save-regions! cat id regions)
      (let [summary (read!)]
        (is (true? (:part/has-regions? summary)))
        (is (not (contains? summary :part/paint-regions)))
        (is (not (contains? summary :part/mounts)))
        (is (< (count (pr-str summary)) 2000)))
      (catalog/save-regions! cat id (assoc regions :faces {}))
      (is (false? (:part/has-regions? (read!))))
      (finally (fixture/stop! started)))))
