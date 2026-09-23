(ns shipyard.e2e.part-regions-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [shipyard.fixtures :as fixtures]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.catalog.sidecar :as sidecar]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.e2e.detail-brush-test :as brush]
            [shipyard.e2e.paint-editor-test :as editor]
            [shipyard.e2e.paint-material-test :as materials]
            [shipyard.e2e.metallic-details-test :as metallic]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as loadouts]
            [shipyard.scheme.db :as schemes]
            [shipyard.scheme.material :as material])
  (:import [com.microsoft.playwright Page Dialog]
           [java.util.function Consumer]))

(defn region-point [driver ordinal]
  (let [point (nth (filter :front? (:region-faces (s/stats driver))) ordinal)
        [x y] (s/js driver "() => {const r=document.querySelector('canvas').getBoundingClientRect();return [r.x,r.y];}")]
    [(+ x (:x point)) (+ y (:y point))]))

(defn set-layer! [driver layer color metal]
  (s/click! driver (str "#paint-target button[data-paint-target='layer/" layer "']"))
  (is (s/wait-until #(= (str "layer/" layer) (s/js driver "() => document.querySelector('#paint-material')?.elements.target.value"))))
  (editor/input! driver "#paint-material input[name=base]" color "input")
  (editor/input! driver "#paint-material input[name=metalness]" metal "input")
  (s/click! driver "#paint-material button.paint-primary")
  (is (s/wait-until #(= "Material saved." (s/text driver "#paint-status")))))

(defn face [driver slot key]
  (first (filter #(= key (:key %)) (:face-finishes (materials/slot driver slot)))))

(deftest reusable-regions-schemes-and-overrides
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        id (:weapon fixture/ids) cat (:shipyard.catalog/db sys) store (:shipyard.scheme/db sys)
        regions #(catalog/part-regions (catalog/part (catalog/snapshot! cat) id))]
    (try
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft :root (str (:root started)))
      (s/go! driver (s/base-url sys))
      (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
      (s/wait-visible! driver ".detail--ready")
      (is (s/wait-until #(seq (:region-faces (s/stats driver)))))
      (s/click! driver "[data-detail-tab=regions]")
      (s/fill-and-blur! driver "#part-regions input[name=name]" "Trim")
      (s/click! driver "button:text-is('Add detail layer')")
      (is (s/wait-until #(= ["Primary" "Secondary" "Trim"] (:layers (regions)))))
      (s/select-option! driver "#region-stroke select" "Secondary")
      (s/check! driver "#region-stroke input[name=enabled]")
      (is (s/wait-until #(false? (:mount-colors-enabled (s/stats driver)))))
      (editor/input! driver "#region-stroke input[name=radius]" "2" "input")
      (apply brush/stroke! driver (region-point driver 0))
      (is (s/wait-until #(seq (:faces (regions)))))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (let [before (regions) file (sidecar/sidecar-file (str (:root started)) id)
            backup (fs/path (:temp started) "regions-backup.edn")]
        (fs/move file backup) (fs/create-dirs file)
        (apply brush/stroke! driver (region-point driver 1))
        (s/wait-visible! driver "#part-regions [role=alert]")
        (is (= before (regions)))
        (is (s/wait-until #(false? (s/js driver "() => document.querySelector('[data-workspace-mode=assembly]').disabled"))))
        (fs/delete-tree file) (fs/move backup file)
        (s/check! driver "#region-stroke input[name=enabled]")
        (editor/input! driver "#region-stroke input[name=radius]" "2" "input"))
      (let [secondary (first (keys (:faces (regions))))]
        (s/select-option! driver "#region-stroke select" "Trim")
        (apply brush/stroke! driver (region-point driver 1))
        (is (s/wait-until #(some #{"Trim"} (vals (:faces (regions))))))
        (let [trim (first (keep (fn [[key layer]] (when (= "Trim" layer) key)) (:faces (regions))))]
          (is (= (regions) (:part/paint-regions (sidecar/read-sidecar! (str (:root started)) id))))
          (is (nil? (catalog/part-regions (catalog/part (catalog/snapshot! cat) (:weapon-alt fixture/ids)))))
          (let [camera (:camera (s/stats driver))]
            (apply brush/right-stroke! driver (region-point driver 1))
            (is (s/wait-until #(not (contains? (:faces (regions)) trim))))
            (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
            (is (= camera (:camera (s/stats driver))))
            (is (= "Trim" (s/js driver "() => document.querySelector('#region-stroke select').value")))
            (apply brush/stroke! driver (region-point driver 1))
            (is (s/wait-until #(= "Trim" (get-in (regions) [:faces trim])))))
          (s/click! driver ".part__select:has(.part__name:text-is('hull'))")
          (s/wait-visible! driver ".detail--ready")
          (s/await-part driver (:hull fixture/ids))
          (s/click! driver "[data-detail-tab=regions]")
          ;; Select the existing shared name without defining it on this part.
          (s/select-option! driver "#region-stroke select" "Trim")
          (s/check! driver "#region-stroke input[name=enabled]")
          (editor/input! driver "#region-stroke input[name=radius]" "2" "input")
          (apply brush/stroke! driver (region-point driver 0))
          (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
          (is (some #{"Trim"} (vals (:faces (catalog/part-regions (catalog/part (catalog/snapshot! cat) (:hull fixture/ids)))))))
          (s/screenshot-el! driver "body" (java.io.File. "/tmp/shipyard-part-regions.png"))
          (workspace/switch! driver "assembly") (workspace/await-ship! driver)
          (s/click! driver "button:text-is('Paint assembly')")
          (s/click! driver ".paint-scheme-actions summary:text-is('New')")
          (s/fill-and-blur! driver "#paint-create input" "Region palette")
          (s/click! driver "#paint-create button")
          (s/wait-visible! driver "#paint-material") (workspace/await-ship! driver)
          (set-layer! driver "Primary" "#0000ff" "0")
          (set-layer! driver "Secondary" "#00ff00" "0.3")
          (set-layer! driver "Trim" "#d4af37" "1")
          (let [hull-trim (first (keys (:faces (catalog/part-regions (catalog/part (catalog/snapshot! cat) (:hull fixture/ids))))))]
            (is (s/wait-until #(metallic/near? 1 (:metalness (face driver [] hull-trim)))))
            (is (= (:base (face driver [] hull-trim)) (:base (face driver [["weapon" 0]] trim)))))
          (doseq [slot [[["weapon" 0]] [["weapon" 1]]]]
            (is (s/wait-until #(metallic/near? 1 (:metalness (face driver slot trim)))))
            (is (metallic/near? 0.3 (:metalness (face driver slot secondary))))
            (is (every? true? (map metallic/near? (map material/srgb->linear [0 1 0]) (:base (face driver slot secondary))))))
          (s/screenshot-el! driver "body" (java.io.File. "/tmp/shipyard-region-palette.png"))
          (s/click! driver "#paint-target button[data-paint-target='[[:weapon 0]]']")
          (is (s/wait-until #(= "[[:weapon 0]]" (s/js driver "() => document.querySelector('#paint-material')?.elements.target.value"))))
          (editor/input! driver "#paint-material input[name=base]" "#ff0000" "input")
          (editor/input! driver "#paint-material input[name=metalness]" "0.5" "input")
          (s/click! driver "#paint-material button.paint-primary")
          (is (s/wait-until #(= "Material saved." (s/text driver "#paint-status"))))
          (is (= "ff0000" (:color (materials/slot driver [["weapon" 0]]))))
          (is (metallic/near? 1 (:metalness (face driver [["weapon" 1]] trim))))
          (s/click! driver "button:text-is('Use inherited material')")
          (is (s/wait-until #(metallic/near? 1 (:metalness (face driver [["weapon" 0]] trim)))))
          (workspace/switch! driver "assembly") (workspace/switch! driver "paint") (workspace/await-ship! driver)
          (is (metallic/near? 1 (:metalness (face driver [["weapon" 1]] trim))))
          (s/click! driver ".paint-scheme-actions summary:text-is('New')")
          (s/fill-and-blur! driver "#paint-create input" "Another palette")
          (s/click! driver "#paint-create button")
          (s/wait-visible! driver "#paint-material")
          (set-layer! driver "Trim" "#ffffff" "0.1")
          (is (s/wait-until #(metallic/near? 0.1 (:metalness (face driver [["weapon" 1]] trim)))))
          (is (= 2 (count (:schemes (schemes/snapshot! store)))))
          (s/screenshot-el! driver "body" (java.io.File. "/tmp/shipyard-part-layers.png"))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest confirmed-scheme-deletion
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver) store (:shipyard.scheme/db sys)
        id (random-uuid) ship {:loadout/id (random-uuid) :loadout/name "Flagship" :loadout/hull (:hull fixture/ids) :loadout/slots {} :loadout/scheme id}
        confirmation (atom nil)]
    (try
      (schemes/put! store {:scheme/id id :scheme/name "Shared palette" :scheme/roles {}} :create)
      (loadouts/put! (:shipyard.loadout/db sys) ship :create)
      (swap! (:state (:shipyard.paint/db sys)) assoc :draft {:revision 1 :hull (:hull fixture/ids) :assignments {} :scheme id} :root (str (:root started)))
      (s/go! driver (s/base-url sys)) (workspace/switch! driver "paint")
      (s/wait-visible! driver "#paint-delete")
      (.onceDialog ^Page (:page driver) (reify Consumer (accept [_ d] (reset! confirmation (.message ^Dialog d)) (.dismiss ^Dialog d))))
      (s/click! driver "button:text-is('Delete scheme')")
      (is (re-find #"Flagship" @confirmation))
      (is (get-in (schemes/snapshot! store) [:schemes id]))
      (.onceDialog ^Page (:page driver) (reify Consumer (accept [_ d] (.accept ^Dialog d))))
      (s/click! driver "button:text-is('Delete scheme')")
      (is (s/wait-until #(empty? (:schemes (schemes/snapshot! store)))))
      (is (= id (get-in (loadouts/snapshot! (:shipyard.loadout/db sys)) [:loadouts (:loadout/id ship) :loadout/scheme])))
      (workspace/switch! driver "ships")
      (s/click! driver "button:text-is('Flagship')")
      (is (s/wait-until #(re-find #"(?i)unavailable|missing" (s/text driver "#detail"))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest detail-layer-management
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids)
        regions #(catalog/part-regions (catalog/part (catalog/snapshot! cat) id))]
    (try
      (s/go! driver (s/base-url sys))
      (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
      (s/wait-visible! driver ".detail--ready")
      (is (s/wait-until #(seq (:region-faces (s/stats driver)))))
      (s/click! driver "[data-detail-tab=regions]")
      (s/fill-and-blur! driver "#part-regions input[name=name]" "Trim")
      (s/click! driver "button:text-is('Add detail layer')")
      (s/click! driver "summary:text-is('Manage Trim')")
      (s/fill-and-blur! driver "#part-regions details[open] input[name=name]" "Accent")
      (s/click! driver "button:text-is('Rename layer')")
      (is (s/wait-until #(= ["Primary" "Secondary" "Accent"] (:layers (regions)))))
      (s/check! driver "#region-stroke input[name=enabled]")
      (is (s/wait-until #(false? (:mount-colors-enabled (s/stats driver)))))
      (apply brush/stroke! driver (region-point driver 0))
      (is (s/wait-until #(some #{"Accent"} (vals (:faces (regions))))))
      (s/click! driver "summary:text-is('Manage Accent')")
      (.onceDialog ^Page (:page driver) (reify Consumer (accept [_ d] (.dismiss ^Dialog d))))
      (s/click! driver "button:text-is('Delete layer')")
      (is (some #{"Accent"} (:layers (regions))))
      (.onceDialog ^Page (:page driver) (reify Consumer (accept [_ d] (.accept ^Dialog d))))
      (s/click! driver "button:text-is('Delete layer')")
      (is (s/wait-until #(= ["Primary" "Secondary"] (:layers (regions)))))
      (is (empty? (:faces (regions))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest dense-regions-save-and-resume
  (s/assert-bundle!)
  (let [id (:weapon fixture/ids)
        started (fixture/start!
                 true (fn [root]
                        (fixture/library! root)
                        (with-open [out (io/output-stream (fs/file root id "unsupported.stl"))]
                          (.write out ^bytes (fixtures/->binary-stl (fixtures/uv-sphere 1.0 64 96))))
                        root))
        sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys)
        regions #(catalog/part-regions (catalog/part (catalog/snapshot! cat) id))]
    (try
      (s/go! driver (s/base-url sys))
      (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
      (s/wait-visible! driver ".detail--ready")
      (is (s/wait-until #(= "loaded" (:status (s/stats driver)))))
      (s/click! driver "[data-detail-tab=regions]")
      (s/check! driver "#region-stroke input[name=enabled]")
      (is (s/wait-until #(false? (:mount-colors-enabled (s/stats driver)))))
      (editor/input! driver "#region-stroke input[name=radius]" "100" "input")
      (let [center (s/js driver "() => {const c=document.querySelector('canvas').getBoundingClientRect();return [c.x+c.width/2,c.y+c.height/2];}")]
        (apply brush/stroke! driver center)
        (is (s/wait-until #(> (count (:faces (regions))) 100)))
        (when-not (s/wait-until #(= "Regions saved." (s/text driver "#region-status")))
          (throw (ex-info "Dense region save response failed" {:status (s/text driver "#region-status")})))
        (is (> (count (pr-str (regions))) 8192) "Region payload exceeds Jetty's response header budget")
        (apply brush/stroke! driver center)
        (is (s/wait-until #(= 2 (:revision (regions)))))
        (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status")))))
      (let [saved (regions)]
        (is (= saved (:part/paint-regions (sidecar/read-sidecar! (str (:root started)) id))))
        (workspace/switch! driver "assembly")
        (workspace/switch! driver "browse")
        (s/wait-visible! driver ".detail--ready")
        (is (s/wait-until #(= {:faces (count (:faces saved)) :vertex-colors true} (:region-preview (s/stats driver)))))
        (s/go! driver (s/base-url sys))
        (s/wait-visible! driver ".detail--ready")
        (is (s/wait-until #(= {:faces (count (:faces saved)) :vertex-colors true} (:region-preview (s/stats driver)))))
        (s/click! driver "[data-detail-tab=regions]")
        (s/check! driver "#region-stroke input[name=enabled]")
        (apply brush/stroke! driver (s/js driver "() => {const c=document.querySelector('canvas').getBoundingClientRect();return [c.x+c.width/2,c.y+c.height/2];}"))
        (is (s/wait-until #(= 3 (:revision (regions)))))
        (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
        (is (every? #(= "Secondary" (get-in (regions) [:faces %])) (keys (:faces saved)))))
      (finally (s/quit! driver) (fixture/stop! started)))))
