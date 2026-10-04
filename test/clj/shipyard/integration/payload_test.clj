(ns shipyard.integration.payload-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.http.urls :as urls]
            [shipyard.bulk-orientation.handlers :as bulk]
            [shipyard.catalog.db :as catalog]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as loadouts]
            [shipyard.scheme.db :as schemes]
            [shipyard.library.index :as index]
            [shipyard.paint.strokes :as strokes]
            [shipyard.region-fixture :as rf]))

(deftest control-responses-do-not-scale-with-dense-face-masks
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        deps (lf/deps started) cat (:catalog deps)
        post #(handler (mock/request :post %1 %2)) get! #(handler (mock/request :get %))
        class-id (random-uuid) scheme-id (random-uuid)
        keys (mapv #(format "%072x" %) (range 10000))
        compact! (fn [response]
                   (is (= 200 (:status response)))
                   (is (< (count (:body response)) 150000))
                   (is (not (str/includes? (:body response) (last keys)))))]
    (try
      (doseq [id (cons (:hull lf/draft) (distinct (vals lf/assignments)))]
        (let [deadline (+ (System/currentTimeMillis) 30000)
              part (:part (catalog/part-context! cat id))
              prepared (loop [] (let [entry (bulk/grid-entry! deps part)]
                                  (if (or (= :ready (:state entry)) (> (System/currentTimeMillis) deadline)) entry
                                      (do (Thread/sleep 20) (recur)))))]
          (is (= :ready (:state prepared)))
          (when (#{(:hull fixture/ids) (:weapon fixture/ids)} id)
            (catalog/save-regions! cat id {:version 2 :layer-definitions {} :mesh-key (:mesh-key prepared) :revision 0
                                           :layers ["Primary" "Secondary"] :faces (zipmap keys (repeat "Secondary"))}))))
      (loadouts/put! (:shipyard.loadout/db sys) {:loadout/id class-id :loadout/name "Payload test"
                                                 :loadout/hull (:hull lf/draft) :loadout/slots lf/assignments} :create)
      (schemes/put! (:shipyard.scheme/db sys) {:scheme/id scheme-id :scheme/name "Palette"
                                               :scheme/layers {"Primary" {:base [1 0 0] :metalness 0.1 :roughness 0.5}}} :create)
      (compact! (get! "/ships"))
      (compact! (post "/ships/edit" {:id (str class-id)}))
      (compact! (post "/assembly/assign" {:revision (str (get-in @(:state (:assembly deps)) [:draft :revision]))
                                          :slot "[[:prow 0]]" :part-id (:prow fixture/ids)}))
      (let [request (assoc-in (mock/request :get "/assembly?poll=1") [:headers "x-shipyard-scene-sequence"] "-1")
            response (handler request)]
        (compact! response)
        (is (str/includes? (:body response) ":reset") "A missed response forces a fresh scene snapshot"))
      (compact! (post "/assembly/drawer" {:revision (str (get-in @(:state (:assembly deps)) [:draft :revision])) :slot "[[:weapon 0]]" :open "true"}))
      (get! "/ships/tab/schemes")
      (let [response (post "/ships/schemes/select" {:id (str scheme-id)})]
        (is (str/includes? (:body response) ":region-data"))
        (is (= 2 (count (re-seq (re-pattern (last keys)) (:body response)))) "Each part's masks appear once despite repeated mounts"))
      (compact! (post "/ships/schemes/layer" {:layer "Secondary"}))
      (get! "/ships/tab/paint")
      (post "/ships/paint/create" {:name "Painted" :class (str class-id) :scheme (str scheme-id)})
      (compact! (get! "/ships/customize/ships?page=1"))
      (get! "/workspace/browse")
      (get! (str "/part/" (urls/encode-id (:hull fixture/ids))))
      (compact! (post "/parts/metadata/individual" {:part-id (:hull fixture/ids) :name "hull" :bundle "Synthetic Navy" :class "Cruiser" :role "hull"}))
      (is (seq (get-in (catalog/part-context! cat (:hull fixture/ids)) [:part :part/paint-regions :faces])))
      (let [id (:hull fixture/ids) mesh-key (index/mesh-key! (:library deps) id)
            before (get-in (catalog/part-context! cat id) [:part :part/paint-regions])
            body (rf/cbor-stroke {:part-id id :mesh-key mesh-key :revision (:revision before)
                                  :action "assign" :layer "Secondary" :mode "facets" :angle "1"
                                  :layer-revision (:revision (catalog/region-registry! cat))}
                                 (count (strokes/ordered-face-keys! (assoc deps :paint (:shipyard.paint/db sys)) mesh-key)) "indices" (rf/uint32-bytes [0]))
            response (handler (-> (mock/request :post "/parts/regions/stroke")
                                  (mock/content-type "application/cbor") (mock/body body)))]
        (compact! response)
        (is (str/includes? (:body response) "data-region-delta"))
        (is (not (str/includes? (:body response) "data-region-faces"))))
      (finally (fixture/stop! started)))))

(deftest palettes-reject-instance-details
  (let [started (fixture/start!) scheme-db (:shipyard.scheme/db (:system started))
        id (random-uuid)
        record {:scheme/id id :scheme/name "Palette"
                :scheme/layers {"Primary" {:base [1 0 0] :metalness 0.1 :roughness 0.5}}
                :scheme/details {[] {:part-id (:hull fixture/ids) :mesh-key (apply str (repeat 64 "a"))
                                     :faces (zipmap (map #(format "%072x" %) (range 10000)) (repeat [1 0 0]))}}}]
    (try
      (is (= :invalid-scheme (:error (schemes/put! scheme-db record :create))))
      (is (empty? (schemes/listing! scheme-db)))
      (is (not (:error (schemes/put! scheme-db (dissoc record :scheme/details) :create))))
      (is (= {id {:scheme/id id :scheme/name "Palette"}} (schemes/listing! scheme-db)))
      (is (= (select-keys (schemes/record! scheme-db id) [:scheme/id :scheme/name :scheme/layers])
             (schemes/palette! scheme-db id)))
      (is (< (count (pr-str (schemes/palette! scheme-db id))) 500))
      (is (nil? (:scheme/details (schemes/record! scheme-db id))))
      (finally (fixture/stop! started)))))
