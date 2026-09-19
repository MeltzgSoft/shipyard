(ns shipyard.integration.detail-brush-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.fixtures :as fixtures]
            [shipyard.library.index :as index]
            [shipyard.mesh.cache :as cache]
            [shipyard.paint.strokes :as strokes]
            [shipyard.scheme.db :as schemes]
            [shipyard.wire :as wire])
  (:import [java.nio.file Files]))

(deftest source-identity-membership-and-admission
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        assembly (:shipyard.assembly/db sys) paint (:shipyard.paint/db sys)
        store (:shipyard.scheme/db sys) library (:shipyard.library/index sys) cache (:shipyard.mesh/cache sys)
        id (:hull fixture/ids) source (index/fresh-source-file! library id)
        mesh-key (:mesh-key (cache/ensure! cache source))
        face (first (strokes/mesh-faces (wire/decode (Files/readAllBytes (fs/path (cache/tier-file cache mesh-key 0))))))
        post #(handler (mock/request :post %1 %2))]
    (try
      (index/record-mesh-key! library id mesh-key 12)
      (swap! (:state assembly) assoc :draft {:revision 1 :hull id :assignments {}} :root (str (:root started)))
      (post "/assembly/paint" {}) (post "/paint/create" {:name "Faces"})
      (let [scheme (get-in @(:state paint) [:draft :scheme])
            params {:id (str scheme) :target "[]" :mesh-key mesh-key :sequence "1"
                    :color "#ff0000" :operation "paint" :faces (pr-str [face])}]
        (is (str/includes? (:body (post "/paint/stroke" params)) "Details saved"))
        (let [before (schemes/snapshot! store)]
          (is (str/includes? (:body (post "/paint/stroke" params)) "selection changed"))
          (is (str/includes? (:body (post "/paint/stroke" (assoc params :sequence "2" :faces (pr-str [(apply str (repeat 72 "a"))])))) "Invalid"))
          (is (= 204 (:status (handler (-> (mock/request :post "/paint/stroke" (assoc params :sequence "3"))
                                           (mock/header "X-Shipyard-Workspace" "paint")
                                           (mock/header "X-Shipyard-Activation" "0"))))))
          (is (= before (schemes/snapshot! store)))
          (with-open [out (io/output-stream source)] (.write out ^bytes (fixtures/->binary-stl (fixtures/cube 5.0))))
          (is (str/includes? (:body (post "/paint/stroke" (assoc params :sequence "4"))) "Source mesh changed"))
          (is (= before (schemes/snapshot! store)))
          (index/set-root! library (:root started))
          (let [new-key (:mesh-key (cache/ensure! cache source))]
            (index/record-mesh-key! library id new-key 12)
            (is (str/includes? (:body (handler (mock/request :get "/paint"))) "changed part or source mesh"))
            (is (str/includes? (:body (post "/paint/stroke" (assoc params :sequence "5" :mesh-key new-key :history "clear"))) "Details saved"))
            (is (nil? (get-in (schemes/snapshot! store) [:schemes scheme :scheme/details []])))
            (is (str/includes? (:body (post "/paint/stroke" (assoc params :sequence "6" :mesh-key new-key :history "undo"))) "Details saved"))
            (is (= before (schemes/snapshot! store)) "Undo clear recovers the retained incompatible layer"))))
      (finally (fixture/stop! started)))))
