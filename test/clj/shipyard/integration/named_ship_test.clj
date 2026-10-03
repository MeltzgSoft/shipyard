(ns shipyard.integration.named-ship-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [datalevin.core :as d]
            [shipyard.store.db :as store]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as classes]
            [shipyard.ship.db :as ships]
            [shipyard.scheme.db :as schemes]
            [shipyard.scheme.presets :as presets]
            [shipyard.persistence-fixture :as persisted]))

(deftest class-palette-and-named-ship-lifecycle
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        class-db (:shipyard.loadout/db sys) ship-db (:shipyard.ship/db sys) scheme-db (:shipyard.scheme/db sys)
        paint (:shipyard.paint/db sys)
        class {:loadout/id (random-uuid) :loadout/name "Cruiser" :loadout/hull (:hull lf/draft) :loadout/slots lf/assignments}
        post #(handler (mock/request :post %1 %2))
        enter #(handler (mock/request :get (str "/workspace/" %)))
        record #(get-in (ships/snapshot! ship-db) [:ships %])]
    (try
      (is (not (:error (classes/put! class-db class :create))))
      (enter "ships")
      (post "/ships/edit" {:id (str (:loadout/id class))})
      (is (= 200 (:status (post "/ships/schemes/create" {:name "Fleet"}))))
      (let [scheme-id (-> (schemes/snapshot! scheme-db) :schemes keys first)]
        (doseq [[url params] [["/ships/schemes/layer" {:layer "Primary"}]
                              ["/ships/schemes/material" {:id (str scheme-id) :layer "Primary" :sequence "1" :base "#0000ff" :metalness "0.3" :roughness "0.6"}]]]
          (let [response (post url params)]
            (is (= 200 (:status response)))
            (is (< (count (:body response)) 20000) "Palette responses contain controls, not ship geometry or face masks")
            (is (not (str/includes? (:body response) "data-assembly-event")))
            (is (not (str/includes? (:body response) "ship-card__load")))))
        (is (= [0.0 0.0 1.0] (get-in (schemes/snapshot! scheme-db) [:schemes scheme-id :scheme/layers "Primary" :base])))
        (post "/ships/paint" {})
        (is (empty? (:ships (ships/snapshot! ship-db))) "Preview does not create a ship")
        (post "/ships/paint/create" {:name "Resolute" :class (str (:loadout/id class)) :scheme (str scheme-id)})
        (let [id (get-in @(:state paint) [:draft :ship-id])]
          (is (= "Resolute" (:ship/name (record id))))
          (is (= (:loadout/id class) (:ship/class (record id))))
          (is (= scheme-id (:ship/scheme (record id))))
          (ships/put! ship-db (assoc-in (record id) [:ship/paint :paint/details []]
                                        (lf/detail-layer! sys (:hull fixture/ids) {:base [1 0 0] :metalness 0.8 :roughness 0.2})) :update)
          (post "/ships/paint/create" {:name "Intrepid" :class (str (:loadout/id class)) :scheme (str scheme-id)})
          (let [sibling (get-in @(:state paint) [:draft :ship-id])]
            (is (not= id sibling))
            (is (empty? (:paint/details (:ship/paint (record sibling))))))
          (post "/ships/paint/select" {:id (str id)})
          (post "/ships/paint/select" {:scheme ""})
          (is (nil? (:ship/scheme (record id))))
          (is (seq (:paint/details (:ship/paint (record id)))))
          (testing "Named ships follow their class without overwriting custom paint"
            (classes/put! class-db (assoc-in class [:loadout/slots [[:weapon 0]]] (:weapon-alt fixture/ids)) :update)
            (handler (mock/request :get "/ships?poll=1"))
            (is (= (:weapon-alt fixture/ids) (get-in @(:state paint) [:draft :assignments [[:weapon 0]]])))
            (is (seq (:paint/details (:ship/paint (record id))))))
          (is (= (ships/snapshot! ship-db) (persisted/records! ship-db :ships)))
          (post "/ships/paint/reset" {:id (str id) :confirmed "true"})
          (is (empty? (:paint/details (:ship/paint (record id)))))
          (is (= "Resolute" (:ship/name (record id))))))
      (finally (fixture/stop! started)))))

(deftest shared-color-presets-persist-independently-of-schemes
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        facade (:shipyard.scheme/db sys) post #(handler (mock/request :post %1 %2))]
    (try
      (handler (mock/request :get "/workspace/ships"))
      (is (= 200 (:status (post "/ships/schemes/presets/add" {:base "#D4AF37"}))))
      (post "/ships/schemes/presets/add" {:base "#d4af37"})
      (is (= ["#d4af37"] (presets/colors! facade)))
      (is (= ["#d4af37"] (persisted/persisted! facade #(sort (d/q '[:find [?hex ...] :where [_ :color-preset/hex ?hex]] %)))))
      (is (= 400 (:status (post "/ships/schemes/presets/add" {:base "invalid"}))))
      (post "/ships/schemes/create" {:name "Other palette"})
      (is (= ["#d4af37"] (presets/colors! facade)))
      (is (= 200 (:status (post "/ships/schemes/presets/remove" {:base "#d4af37"}))))
      (is (empty? (presets/colors! facade)))
      (finally (fixture/stop! started)))))

(deftest ships-have-independent-detail-ownership
  (let [started (fixture/start!) sys (:system started)
        class-db (:shipyard.loadout/db sys) ship-db (:shipyard.ship/db sys)
        scheme-db (:shipyard.scheme/db sys)
        class {:loadout/id (random-uuid) :loadout/name "Class" :loadout/hull (:hull lf/draft) :loadout/slots lf/assignments}
        palette {:scheme/id (random-uuid) :scheme/name "Palette" :scheme/layers {}}
        details {[] (lf/detail-layer! sys (:hull fixture/ids) {:base [1 0 0] :metalness 0.4 :roughness 0.7})}]
    (try
      (schemes/put! scheme-db palette :create)
      (classes/put! class-db class :create)
      (let [ship {:ship/id (random-uuid) :ship/name "Vessel" :ship/class (:loadout/id class)
                  :ship/scheme (:scheme/id palette) :ship/paint {:paint/details details}}
            sibling (assoc ship :ship/id (random-uuid) :ship/name "Sibling")]
        (is (not (:error (ships/put! ship-db ship :create))))
        (is (not (:error (ships/put! ship-db sibling :create))))
        (ships/put! ship-db (assoc ship :ship/paint {}) :update)
        (is (= details (get-in (ships/snapshot! ship-db) [:ships (:ship/id sibling) :ship/paint :paint/details])))
        (is (= palette (get-in (schemes/snapshot! scheme-db) [:schemes (:scheme/id palette)])))
        (is (= (ships/snapshot! ship-db) (persisted/records! ship-db :ships)))
        (let [before (ships/snapshot! ship-db) write! store/put-ship!]
          (with-redefs [store/put-ship! (fn [conn library record]
                                          (write! conn library record)
                                          (throw (ex-info "Injected transaction failure" {})))]
            (is (:error (ships/put! ship-db (assoc sibling :ship/name "Not committed") :update))))
          (is (= before (ships/snapshot! ship-db)) "A failed graph replacement rolls back atomically"))
        (ships/delete! ship-db (:ship/id ship))
        (is (= #{(:ship/id sibling)} (set (keys (:ships (ships/snapshot! ship-db)))))))
      (finally (fixture/stop! started)))))
