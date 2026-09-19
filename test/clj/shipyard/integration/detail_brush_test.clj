(ns shipyard.integration.detail-brush-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.fixtures :as fixtures]
            [shipyard.library.index :as index]
            [shipyard.loadout-fixture :as lf]
            [shipyard.workspace.db :as workspace]
            [shipyard.mesh.cache :as cache]
            [shipyard.paint.strokes :as strokes]
            [shipyard.scheme.db :as schemes]
            [shipyard.wire :as wire])
  (:import [java.nio.file Files OpenOption StandardOpenOption]
           [java.nio.file.attribute FileTime]))

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
          ;; Preprocessing memory-maps the source. Windows permits an in-place
          ;; write, but not the truncation performed by io/output-stream.
          (let [path (fs/path source)
                bytes (fixtures/->binary-stl (fixtures/cube 5.0))
                mtime (.toMillis (Files/getLastModifiedTime path (make-array java.nio.file.LinkOption 0)))]
            (is (= (Files/size path) (alength ^bytes bytes)) "Replacement fits the existing mapping")
            (Files/write path ^bytes bytes (into-array OpenOption [StandardOpenOption/WRITE]))
            (is (java.util.Arrays/equals ^bytes bytes (Files/readAllBytes path)))
            ;; Same-size edits must change the scan stamp even on coarse clocks.
            (Files/setLastModifiedTime path (FileTime/fromMillis (+ mtime 2000))))
          (is (str/includes? (:body (post "/paint/stroke" (assoc params :sequence "4"))) "Source mesh changed"))
          (is (= before (schemes/snapshot! store)))
          (index/set-root! library (:root started))
          (let [new-key (:mesh-key (cache/ensure! cache source))]
            (is (not= mesh-key new-key) "The source content, not just its timestamp, changed")
            (index/record-mesh-key! library id new-key 12)
            (is (str/includes? (:body (handler (mock/request :get "/paint"))) "changed part or source mesh"))
            (is (str/includes? (:body (post "/paint/stroke" (assoc params :sequence "5" :mesh-key new-key :history "clear"))) "Details saved"))
            (is (nil? (get-in (schemes/snapshot! store) [:schemes scheme :scheme/details []])))
            (is (str/includes? (:body (post "/paint/stroke" (assoc params :sequence "6" :mesh-key new-key :history "undo"))) "Details saved"))
            (is (= before (schemes/snapshot! store)) "Undo clear recovers the retained incompatible layer"))))
      (finally (fixture/stop! started)))))

(deftest streamed-cross-instance-stroke-is-one-atomic-history-entry
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        paint (:shipyard.paint/db sys) store (:shipyard.scheme/db sys)
        library (:shipyard.library/index sys) cache (:shipyard.mesh/cache sys)
        part-id (:weapon fixture/ids) source (index/fresh-source-file! library part-id)
        mesh-key (:mesh-key (cache/ensure! cache source))
        face-keys (vec (strokes/mesh-faces (wire/decode (Files/readAllBytes (fs/path (cache/tier-file cache mesh-key 0))))))
        post #(handler (mock/request :post "/paint/stroke" %))
        entry (fn [path key] {:target (pr-str path) :mesh-key mesh-key :faces [key]})]
    (try
      (index/record-mesh-key! library part-id mesh-key 12)
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft :root (str (:root started)))
      (handler (mock/request :post "/assembly/paint" {}))
      (handler (mock/request :post "/paint/create" {:name "Streamed"}))
      (let [id (get-in @(:state paint) [:draft :scheme]) stroke-id (str (random-uuid))
            params {:id (str id) :target "[]" :stroke-id stroke-id :part "0" :final "false"
                    :sequence "1" :color "#ff0000" :metalness "0.8" :roughness "0.2" :operation "paint"
                    :entries (pr-str [(entry [[:weapon 0]] (first face-keys)) (entry [[:weapon 1]] (first face-keys))])}
            masks #(get-in (schemes/snapshot! store) [:schemes id :scheme/details])
            history #(get-in (workspace/workspace! (:shipyard.workspace/db sys) :paint) [:brush-history :undo])
            before (slurp (str (:file store)))]
        (is (= 204 (:status (post params))))
        (is (= before (slurp (str (:file store)))))
        (is (empty? (history)))
        (is (str/includes? (:body (post (assoc params :sequence "2" :history "undo"))) "Finish the current stroke"))
        (let [final (assoc params :part "1" :final "true" :sequence "3"
                           :entries (pr-str [(entry [[:weapon 1]] (second face-keys))]))]
          (is (str/includes? (:body (post final)) "Details saved"))
          (is (= #{[[:weapon 0]] [[:weapon 1]]} (set (keys (masks)))))
          (is (= 2 (count (get-in (masks) [[[:weapon 1]] :faces]))))
          (is (= #{{:base [1.0 0.0 0.0] :metalness 0.8 :roughness 0.2}}
                 (set (mapcat #(vals (:faces %)) (vals (masks))))))
          (is (= 1 (count (history))))
          (let [saved (schemes/snapshot! store)]
            (is (str/includes? (:body (post (assoc final :sequence "4"))) "Details saved"))
            (is (= saved (schemes/snapshot! store)))
            (is (= 1 (count (history))) "Lost acknowledgement retry is idempotent")
            (post (assoc final :sequence "5" :history "undo"))
            (is (empty? (masks)))
            (post (assoc final :sequence "6" :history "redo"))
            (is (= saved (schemes/snapshot! store)))))
        (let [bad (assoc params :stroke-id (str (random-uuid)) :sequence "7")
              saved (schemes/snapshot! store)]
          (is (= 204 (:status (post bad))))
          (is (str/includes? (:body (post (assoc bad :part "1" :sequence "8" :final "true"
                                                 :entries (pr-str [(assoc (entry [[:weapon 1]] (first face-keys)) :mesh-key (apply str (repeat 64 "f")))])))) "Source mesh changed"))
          (is (= saved (schemes/snapshot! store)) "One bad instance rejects every buffered part")
          (is (= 1 (count (history)))))
        (let [cancel (assoc params :stroke-id (str (random-uuid)) :sequence "9")]
          (is (= 204 (:status (post cancel))))
          (is (= 204 (:status (post (assoc cancel :sequence "10" :operation "cancel")))))
          (is (nil? (:brush-pending (workspace/workspace! (:shipyard.workspace/db sys) :paint))))
          (is (str/includes? (:body (post (assoc cancel :sequence "11" :history "undo"))) "Details saved"))
          (is (empty? (masks)))))
      (finally (fixture/stop! started)))))

(deftest per-face-finish-persistence-and-invalid-input
  (let [started (fixture/start!) sys (:system started) handler (:handler started)
        store (:shipyard.scheme/db sys) library (:shipyard.library/index sys) cache (:shipyard.mesh/cache sys)
        id (:hull fixture/ids) mesh-key (:mesh-key (cache/ensure! cache (index/fresh-source-file! library id)))
        [a b] (vec (strokes/mesh-faces (wire/decode (Files/readAllBytes (fs/path (cache/tier-file cache mesh-key 0))))))
        post #(handler (mock/request :post %1 %2))]
    (try
      (index/record-mesh-key! library id mesh-key 12)
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft {:revision 1 :hull id :assignments {}} :root (str (:root started)))
      (post "/assembly/paint" {}) (post "/paint/create" {:name "Metallic"})
      (let [scheme (get-in @(:state (:shipyard.paint/db sys)) [:draft :scheme])
            params {:id (str scheme) :target "[]" :mesh-key mesh-key :sequence "1"
                    :color "#ffcc00" :operation "paint" :faces (pr-str [a])}]
        (post "/paint/stroke" params)
        (is (vector? (get-in (schemes/snapshot! store) [:schemes scheme :scheme/details [] :faces a])))
        (is (str/includes? (:body (post "/paint/stroke" (assoc params :sequence "2" :faces (pr-str [b]) :metalness "1" :roughness "0.15"))) "Details saved"))
        (is (= {:base [1.0 0.8 0.0] :metalness 1.0 :roughness 0.15}
               (get-in (schemes/snapshot! (schemes/open! (:file store))) [:schemes scheme :scheme/details [] :faces b])))
        (is (vector? (get-in (schemes/snapshot! (schemes/open! (:file store))) [:schemes scheme :scheme/details [] :faces a])))
        (let [before (schemes/snapshot! store) bytes (slurp (fs/file (:file store)))]
          (doseq [[n invalid] (map-indexed vector [{:metalness "NaN" :roughness "0.5"}
                                                   {:metalness "1" :roughness "Infinity"}
                                                   {:metalness "1"} {:metalness "-0.1" :roughness "0.5"}])]
            (is (str/includes? (:body (post "/paint/stroke" (merge params invalid {:sequence (str (+ n 3))}))) "Invalid detail material")))
          (is (= before (schemes/snapshot! store)))
          (is (= bytes (slurp (fs/file (:file store)))))))
      (finally (fixture/stop! started)))))
