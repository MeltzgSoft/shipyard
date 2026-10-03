(ns shipyard.integration.settings-workspace-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.settings.db :as settings]
            [shipyard.store.db :as store]
            [shipyard.vocabulary.db :as vocabulary]
            [shipyard.workspace.db :as workspace]))

(deftest classification-renames-are-atomic-across-libraries-and-preserve-source-identity
  (let [directory (fs/create-temp-dir) database (store/open! directory)
        library-a (random-uuid) library-b (random-uuid) mount-id (random-uuid)
        original-part {:part/key [library-a "a"] :part/id "a" :part/uid (random-uuid)
                       :part/role-hint :sensor :part/revision 2 :part/present? true
                       :part/mounts [{:mount/uid mount-id :mount/id :sensor :mount/kind :socket
                                      :mount/accepts [:sensor :weapon] :mount/pos [1 2 3]
                                      :mount/cut {:kind :pit :depth 1 :diameter 2}}]}]
    (try
      (store/write! database #(d/transact! % [original-part
                                              {:part/key [library-b "missing"] :part/id "missing" :part/present? false
                                               :part/role-override :sensor :part/revision 1}]))
      (is (:error (vocabulary/manage! database :delete "role" "sensor" nil)))
      (let [before (store/read! database #(d/pull % store/part-pattern [:part/key [library-a "a"]]))]
        (is (= {:field :role :old "sensor" :new "radar"} (vocabulary/manage! database :rename "role" "sensor" "radar")))
        (let [after (store/read! database #(d/pull % store/part-pattern [:part/key [library-a "a"]]))
              mount (first (:part/mounts after))]
          (is (= (dissoc before :part/role-override :part/revision :part/mounts)
                 (dissoc after :part/role-override :part/revision :part/mounts)))
          (is (= :radar (:part/role-override after)))
          (is (= 3 (:part/revision after)))
          (is (= #{:radar :weapon} (set (:mount/accepts mount))))
          (is (= {:kind :pit :depth 1 :diameter 2} (:mount/cut mount)))
          (is (= [1 2 3] (:mount/pos mount))))
        (is (= :radar (store/read! database #(:part/role-override (d/pull % [:part/role-override] [:part/key [library-b "missing"]])))))
        (is (= 2 (:parts (some #(when (= "radar" (:value %)) %) (:role (vocabulary/entries! database))))))
        (is (:error (vocabulary/manage! database :delete "role" "radar" nil))))
      (vocabulary/manage! database :add "class" nil "Unused")
      (is (= {} (vocabulary/manage! database :delete "class" "Unused" nil)))
      (is (not (contains? (:class (vocabulary/registered! database)) "Unused")))
      (testing "a failed transaction rolls back every use and registration"
        (let [transact! d/transact!]
          (with-redefs [d/transact! (fn [conn tx] (transact! conn tx) (throw (ex-info "commit failed" {})))]
            (is (thrown? Exception (vocabulary/manage! database :rename "role" "radar" "scanner"))))))
      (is (contains? (:role (vocabulary/registered! database)) "radar"))
      (is (not (contains? (:role (vocabulary/registered! database)) "scanner")))
      (finally (store/close! database) (fs/delete-tree directory)))))

(deftest settings-http-defaults-and-workspace-guards
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        database (:shipyard.store/db sys) ws (:shipyard.workspace/db sys)
        post! #(handler (mock/request :post %1 %2))
        values {"pit-depth" "1.5" "pit-diameter" "4" "recess-depth" "2.5" "recess-border" "0"}]
    (try
      (let [response (handler (assoc (mock/request :get "/workspace/settings") :headers {"hx-request" "true"}))]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "settings-workspace")))
      (is (= :settings (:workspace (workspace/active-context! ws))))
      (is (= 200 (:status (post! "/settings/cuts" values))))
      (is (= {:pit {:depth 1.5 :diameter 4.0} :recess {:depth 2.5 :border 0.0}} (settings/cut-defaults! database)))
      (is (= 422 (:status (post! "/settings/cuts" (assoc values "pit-depth" "NaN")))))
      (is (= 1.5 (get-in (settings/cut-defaults! database) [:pit :depth])))
      (is (= 422 (:status (post! "/settings/classifications/delete" {"field" "role" "value" "weapon"}))))
      (is (= 200 (:status (post! "/settings/classifications/rename" {"field" "bundle" "value" "Synthetic Navy" "new-value" "New Navy"}))))
      (is (= "New Navy" (:part/bundle (catalog/summary! (:shipyard.catalog/db sys) (:hull fixture/ids)))))
      (let [context (workspace/active-context! ws)]
        (workspace/activate! ws :browse)
        (is (= 204 (:status (handler (assoc-in (assoc-in (mock/request :post "/settings/cuts" values)
                                                         [:headers "x-shipyard-workspace"] "settings")
                                               [:headers "x-shipyard-activation"] (str (:activation context))))))))
      (workspace/update-workspace! ws :browse assoc :import {:active true})
      (is (= 409 (:status (post! "/settings/classifications/add" {"field" "class" "value" "Blocked"}))))
      (is (not (contains? (:class (vocabulary/registered! database)) "Blocked")))
      (workspace/update-workspace! ws :browse dissoc :import)
      (finally (workspace/update-workspace! ws :browse dissoc :import) (fixture/stop! started)))))

(deftest cut-defaults-survive-reopening
  (let [directory (fs/create-temp-dir) defaults {:pit {:depth 3.0 :diameter 4.0} :recess {:depth 5.0 :border 0.0}}]
    (try
      (let [database (store/open! directory)]
        (try (settings/save-cut-defaults! database defaults) (finally (store/close! database))))
      (let [database (store/open! directory)]
        (try (is (= defaults (settings/cut-defaults! database))) (finally (store/close! database))))
      (finally (fs/delete-tree directory)))))
