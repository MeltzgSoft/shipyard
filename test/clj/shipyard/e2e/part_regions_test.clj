(ns shipyard.e2e.part-regions-test
  (:require [shipyard.persistence-fixture :as persisted]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [shipyard.fixtures :as fixtures]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.region-fixture :as rf]
            [shipyard.regions.transport :as transport]
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
  (:import [com.microsoft.playwright Page Dialog Request]
           [java.util.function Consumer]))

(defn region-point [driver ordinal]
  ;; Camera matrices and part transforms reach the GPU on the next render.
  ;; Projecting sooner can send a real mouse stroke into the inspector tabs.
  (s/await-rendered-geometries driver)
  (let [point (nth (filter :front? (:region-faces (s/stats driver))) ordinal)
        [left top] (s/js driver "() => {const r=document.querySelector('canvas').getBoundingClientRect();return [r.x,r.y];}")
        x (+ left (:x point)) y (+ top (:y point))]
    (is (= "CANVAS" (s/js driver (str "() => document.elementFromPoint(" x "," y ")?.tagName")))
        "Region strokes target a rendered face outside the inspector")
    [x y]))

(defn set-layer! [driver layer color metal]
  (s/click! driver (str "#paint-target button:has(.paint-target-name:text-is('" layer "'))"))
  (is (s/wait-until #(= layer (s/text driver "#paint-target button[aria-pressed=true] .paint-target-name"))))
  (editor/input! driver "#paint-material input[name=base]" color "input")
  (editor/input! driver "#paint-material input[name=metalness]" metal "input")
  (s/click! driver "#paint-material button.paint-primary")
  (is (s/wait-until #(= "Material saved." (s/text driver "#paint-status")))))

(defn face [driver slot key]
  (first (filter #(= key (:key %)) (:face-finishes (materials/slot driver slot)))))

(defn region-colors [driver]
  (into {} (map (juxt :key :base)) (:region-faces (s/stats driver))))

(deftest selectable-layers-and-face-brush
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids)
        regions #(catalog/part-regions (catalog/part (catalog/snapshot! cat) id))
        selected #(s/js driver "() => document.querySelector('[data-region-layer][aria-pressed=true] .region-layer__name').textContent")
        mode #(s/js driver "() => document.querySelector('[data-region-mode][aria-pressed=true]').dataset.regionMode")]
    (try
      (s/go! driver (s/base-url sys))
      (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
      (s/await-part driver id)
      (s/click! driver "[data-detail-tab=regions]")
      (is (s/wait-until #(false? (:mount-colors-enabled (s/stats driver)))))
      (is (zero? (s/count-els driver "#part-regions select, #part-regions details")))
      (is (zero? (s/count-els driver "button[aria-label='Rename Primary'], button[aria-label='Delete Secondary']")))
      (s/click! driver "button[data-region-layer='Primary']")
      (is (= "Primary" (selected)))
      (s/click! driver "button[data-region-layer='Secondary']")
      (is (= "Secondary" (selected)))
      (is (= "facets" (mode)))
      (editor/input! driver "#region-stroke input[name=radius]" "2" "input")
      (apply brush/stroke! driver (region-point driver 0))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (is (= 1 (count (:faces (regions)))) "Facets affects only the touched triangle")
      (s/click! driver "button[data-region-mode=faces]")
      (apply brush/stroke! driver (region-point driver 0))
      (is (s/wait-until #(= 2 (:revision (regions)))))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (is (= 2 (count (:faces (regions)))) "Faces covers both triangles on this flat cube side")
      (is (= "faces" (mode)))
      (apply brush/right-stroke! driver (region-point driver 0))
      (is (s/wait-until #(= 3 (:revision (regions)))))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (is (empty? (:faces (regions))))
      (is (= "Secondary" (selected)) "Whole-face erasing retains the selected layer")
      (is (= "faces" (mode)))
      (s/fill-and-blur! driver "#region-add input[name=name]" "Panels")
      (s/click! driver "button:text-is('Add layer')")
      (is (s/wait-until #(= "Panels" (selected))))
      (is (= "faces" (mode)) "Layer operations retain the brush mode")
      (s/click! driver "button[data-region-mode=facets]")
      (editor/input! driver "#region-stroke input[name=radius]" "2" "input")
      (apply brush/stroke! driver (region-point driver 0))
      (is (s/wait-until #(= 4 (:revision (regions)))))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (is (= 1 (count (:faces (regions)))))
      (is (= "Panels" (selected)))
      (is (= "facets" (mode)))
      (s/screenshot-el! driver "body" (java.io.File. "/tmp/shipyard-selectable-layers.png"))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest stable-colors-and-global-layer-deletion
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids) other (:weapon-alt fixture/ids)
        regions #(catalog/part-regions (catalog/part (catalog/snapshot! cat) %))]
    (try
      (s/go! driver (s/base-url sys))
      (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
      (s/await-part driver id)
      (s/click! driver "[data-detail-tab=regions]")
      (is (s/wait-until #(false? (:mount-colors-enabled (s/stats driver)))))
      (is (zero? (s/count-els driver "#region-stroke input[name=enabled], [data-authoring-toggle]")))
      (s/fill-and-blur! driver "#region-add input[name=name]" "Trim")
      (s/click! driver "button:text-is('Add layer')")
      (is (s/wait-until #(rf/id cat "Trim")))
      (editor/input! driver "#region-stroke input[name=radius]" "2" "input")
      (apply brush/stroke! driver (region-point driver 0))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (let [before (regions id) colors (region-colors driver)
            swatch (s/js driver "() => document.querySelector('[aria-label=\"Paint Trim\"] .paint-swatch').style.background")]
        (is (seq (:faces before)) "Opening Regions enables painting without a checkbox")
        (s/fill-and-blur! driver "#region-add input[name=name]" "Running Lights")
        (s/click! driver "button:text-is('Add layer')")
        (is (s/wait-until #(rf/id cat "Running Lights")))
        (is (= (:faces before) (:faces (regions id))))
        (is (= colors (region-colors driver)))
        (is (= swatch (s/js driver "() => document.querySelector('[aria-label=\"Paint Trim\"] .paint-swatch').style.background")))
        (s/click! driver "[data-detail-tab=mounts]")
        (is (s/wait-until #(= id (get-in (s/stats driver) [:authoring :part-id]))))
        (is (= "crosshair" (s/js driver "() => getComputedStyle(document.querySelector('canvas')).cursor")))
        (apply s/click-point! driver (region-point driver 0))
        (is (s/wait-until #(= 2 (get-in (s/stats driver) [:preview :triangles]))))
        (is (= (:faces before) (:faces (regions id))))
        (s/click! driver "[data-detail-tab=regions]")
        (is (nil? (:authoring (s/stats driver))))
        (is (nil? (:preview (s/stats driver))))
        (s/click! driver "[data-detail-tab=part]")
        (is (not= "crosshair" (s/js driver "() => getComputedStyle(document.querySelector('canvas')).cursor")))
        (apply s/click-point! driver (region-point driver 0))
        (is (= before (assoc (regions id) :layers (:layers before) :revision (:revision before))))
        (is (nil? (:preview (s/stats driver)))))
      (s/click! driver ".part__select:has(.part__name:text-is('weapon-alt'))")
      (s/await-part driver other)
      (s/click! driver "[data-detail-tab=regions]")
      (s/click! driver "button[aria-label='Paint Trim']")
      (s/click! driver "button:text-is('Apply layer to entire part')")
      (is (s/wait-until #(= 12 (count (:faces (regions other))))))
      ;; A shared type can be deleted from a part that never adopted it.
      (s/click! driver ".part__select:has(.part__name:text-is('hull'))")
      (s/await-part driver (:hull fixture/ids))
      (s/click! driver "[data-detail-tab=regions]")
      (let [layer (rf/id cat "Trim") before (mapv regions [id other])]
        (s/click! driver "button[aria-label='Rename Trim']")
        (s/fill-and-blur! driver ".region-layer__rename:not([hidden]) input[name=name]" "Accent")
        (s/click! driver ".region-layer__rename:not([hidden]) button:text-is('Save name')")
        (is (s/wait-until #(= layer (rf/id cat "Accent"))))
        (is (= (mapv #(dissoc % :layer-definitions) before)
               (mapv #(dissoc (regions %) :layer-definitions) [id other])))
        (doseq [part-id [id other]]
          (is (= {:name "Accent" :preview-name "Trim"}
                 (get-in (regions part-id) [:layer-definitions layer]))))
        (is (nil? (catalog/part-regions (catalog/part (catalog/snapshot! cat) (:hull fixture/ids))))))
      (let [before (mapv regions [id other]) confirmation (atom nil)]
        (.onceDialog ^Page (:page driver) (reify Consumer (accept [_ d] (reset! confirmation (.message ^Dialog d)) (.dismiss ^Dialog d))))
        (s/click! driver "button[aria-label='Delete Accent']")
        (is (re-find #"every part.*Primary" @confirmation))
        (is (= before (mapv regions [id other])))
        (.onceDialog ^Page (:page driver) (reify Consumer (accept [_ d] (.accept ^Dialog d))))
        (s/click! driver "button[aria-label='Delete Accent']")
        (is (s/wait-until #(nil? (rf/id cat "Accent"))))
        (doseq [part-id [id other]]
          (is (empty? (:faces (regions part-id))))
          (is (= (regions part-id) (:part/paint-regions (persisted/authored! (:shipyard.catalog/db (:system started)) part-id))))))
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver ".detail--ready")
      (s/click! driver "[data-detail-tab=regions]")
      (is (= ["Primary" "Secondary" "Running Lights"]
             (s/js driver "() => [...document.querySelectorAll('.region-layer__name')].map(o => o.textContent)")))
      (s/screenshot-el! driver "body" (java.io.File. "/tmp/shipyard-layer-types.png"))
      (finally (s/quit! driver) (fixture/stop! started)))))

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
      (s/fill-and-blur! driver "#region-add input[name=name]" "Trim")
      (s/click! driver "button:text-is('Add layer')")
      (is (s/wait-until #(= ["Primary" "Secondary" "Trim"] (rf/names cat))))
      (s/click! driver "button[data-region-layer='Secondary']")

      (is (s/wait-until #(false? (:mount-colors-enabled (s/stats driver)))))
      (editor/input! driver "#region-stroke input[name=radius]" "2" "input")
      (apply brush/stroke! driver (region-point driver 0))
      (is (s/wait-until #(seq (:faces (regions)))))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (let [before (regions)]
        ;; Legacy files are no longer authoritative after import.
        (spit (sidecar/sidecar-file (str (:root started)) id) "{invalid legacy data")
        (is (= before (:part/paint-regions (persisted/authored! cat id)))))
      (let [secondary (first (keys (:faces (regions))))]
        (s/click! driver "button[aria-label='Paint Trim']")
        (apply brush/stroke! driver (region-point driver 1))
        (is (s/wait-until #(some #{(rf/id cat "Trim")} (vals (:faces (regions))))))
        (let [trim (first (keep (fn [[key layer]] (when (= (rf/id cat "Trim") layer) key)) (:faces (regions))))]
          (is (= (regions) (:part/paint-regions (persisted/authored! (:shipyard.catalog/db (:system started)) id))))
          (is (nil? (catalog/part-regions (catalog/part (catalog/snapshot! cat) (:weapon-alt fixture/ids)))))
          (let [camera (:camera (s/stats driver))]
            (apply brush/right-stroke! driver (region-point driver 1))
            (is (s/wait-until #(not (contains? (:faces (regions)) trim))))
            (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
            (is (= camera (:camera (s/stats driver))))
            (is (= (rf/id cat "Trim") (s/js driver "() => document.querySelector('#region-stroke input[name=layer]').value")))
            (apply brush/stroke! driver (region-point driver 1))
            (is (s/wait-until #(= (rf/id cat "Trim") (get-in (regions) [:faces trim])))))
          (s/click! driver ".part__select:has(.part__name:text-is('hull'))")
          (s/wait-visible! driver ".detail--ready")
          (s/await-part driver (:hull fixture/ids))
          (s/click! driver "[data-detail-tab=regions]")
          ;; Select the existing shared name without defining it on this part.
          (s/click! driver "button[aria-label='Paint Trim']")

          (editor/input! driver "#region-stroke input[name=radius]" "2" "input")
          (apply brush/stroke! driver (region-point driver 0))
          (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
          (is (some #{(rf/id cat "Trim")} (vals (:faces (catalog/part-regions (catalog/part (catalog/snapshot! cat) (:hull fixture/ids)))))))
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
      (s/fill-and-blur! driver "#region-add input[name=name]" "Trim")
      (s/click! driver "button:text-is('Add layer')")
      (s/click! driver "button[aria-label='Rename Trim']")
      (s/fill-and-blur! driver ".region-layer__rename:not([hidden]) input[name=name]" "Discarded")
      (s/click! driver ".region-layer__rename:not([hidden]) button:text-is('Cancel')")
      (is (= ["Primary" "Secondary" "Trim"] (rf/names cat)))
      (is (= "false" (s/js driver "() => document.querySelector('[aria-label=\"Rename Trim\"]').getAttribute('aria-expanded')")))
      (s/click! driver "button[aria-label='Rename Trim']")
      (is (= "Trim" (s/js driver "() => document.querySelector('.region-layer__rename:not([hidden]) input[name=name]').value")))
      (s/fill-and-blur! driver ".region-layer__rename:not([hidden]) input[name=name]" "Accent")
      (s/click! driver ".region-layer__rename:not([hidden]) button:text-is('Save name')")
      (is (s/wait-until #(= ["Primary" "Secondary" "Accent"] (rf/names cat))))

      (is (s/wait-until #(false? (:mount-colors-enabled (s/stats driver)))))
      (apply brush/stroke! driver (region-point driver 0))
      (is (s/wait-until #(some #{(rf/id cat "Accent")} (vals (:faces (regions))))))
      (.onceDialog ^Page (:page driver) (reify Consumer (accept [_ d] (.dismiss ^Dialog d))))
      (s/click! driver "button[aria-label='Delete Accent']")
      (is (some #{(rf/id cat "Accent")} (:layers (regions))))
      (.onceDialog ^Page (:page driver) (reify Consumer (accept [_ d] (.accept ^Dialog d))))
      (s/click! driver "button[aria-label='Delete Accent']")
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
        regions #(catalog/part-regions (catalog/part (catalog/snapshot! cat) id))
        requests (atom [])]
    (try
      (.onRequest ^Page (:page driver)
                  (reify Consumer
                    (accept [_ value]
                      (let [^Request request value]
                        (when (= (str (s/base-url sys) "/parts/regions/stroke") (.url request))
                          (swap! requests conj {:method (.method request) :content-type (.headerValue request "content-type")
                                                :body (.postDataBuffer request)}))))))
      (s/go! driver (s/base-url sys))
      (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
      (s/wait-visible! driver ".detail--ready")
      (is (s/wait-until #(= "loaded" (:status (s/stats driver)))))
      (s/click! driver "[data-detail-tab=regions]")

      (is (s/wait-until #(false? (:mount-colors-enabled (s/stats driver)))))
      (s/click! driver "button[data-region-mode=faces]")
      (editor/input! driver "#region-angle" "31" "input")
      (editor/input! driver "#region-stroke input[name=radius]" "100" "input")
      (let [center (s/js driver "() => {const c=document.querySelector('canvas').getBoundingClientRect();return [c.x+c.width/2,c.y+c.height/2];}")]
        (apply brush/stroke! driver center)
        (is (s/wait-until #(> (count (:faces (regions))) 100)))
        (when-not (s/wait-until #(= "Regions saved." (s/text driver "#region-status")))
          (throw (ex-info "Dense region save response failed" {:status (s/text driver "#region-status")})))
        (is (> (count (pr-str (regions))) 8192) "Region payload exceeds Jetty's response header budget")
        (is (= "POST" (:method (first @requests))))
        (is (= "application/cbor" (:content-type (first @requests))))
        (is (< (count (:body (first @requests))) 4096) "Dense strokes send a compact binary mask")
        (let [decoded (transport/decode (:body (first @requests)))]
          (is (= "bitset" (:encoding decoded)))
          (is (= "faces" (get-in decoded [:metadata "mode"])))
          (is (= "31" (get-in decoded [:metadata "angle"])))
          (is (> (count (:indices decoded)) 100)))
        (is (= (str (s/base-url sys) "/") (.url ^Page (:page driver))) "Painting does not navigate")
        (apply brush/stroke! driver center)
        (is (s/wait-until #(= 2 (:revision (regions)))))
        (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status")))))
      (let [saved (regions)]
        (is (= saved (:part/paint-regions (persisted/authored! (:shipyard.catalog/db (:system started)) id))))
        (workspace/switch! driver "assembly")
        (workspace/switch! driver "browse")
        (s/wait-visible! driver ".detail--ready")
        (is (s/wait-until #(= {:faces (count (:faces saved)) :vertex-colors true} (:region-preview (s/stats driver)))))
        (s/go! driver (s/base-url sys))
        (s/wait-visible! driver ".detail--ready")
        (is (s/wait-until #(= {:faces (count (:faces saved)) :vertex-colors true} (:region-preview (s/stats driver)))))
        (s/click! driver "[data-detail-tab=regions]")

        (apply brush/stroke! driver (s/js driver "() => {const c=document.querySelector('canvas').getBoundingClientRect();return [c.x+c.width/2,c.y+c.height/2];}"))
        (is (s/wait-until #(= 3 (:revision (regions)))))
        (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
        (let [assignments (:faces (regions))]
          (is (every? #(= "Secondary" (get assignments %)) (keys (:faces saved)))))
        ;; Canvas strokes must use explicit background POSTs even if the form's
        ;; submit event is not intercepted by HTMX.
        (s/click! driver "button[data-region-mode=faces]")
        (editor/input! driver "#region-angle" "31" "input")
        (s/js driver "() => document.querySelector('#region-stroke').addEventListener('submit', e => e.stopImmediatePropagation(), {capture:true, once:true})")
        (editor/input! driver "#region-stroke input[name=radius]" "100" "input")
        (apply brush/stroke! driver (s/js driver "() => {const c=document.querySelector('canvas').getBoundingClientRect();return [c.x+c.width/2,c.y+c.height/2];}"))
        (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
        (is (s/wait-until #(= 4 (:revision (regions)))))
        (is (= (str (s/base-url sys) "/") (.url ^Page (:page driver))))
        (is (= 4 (count @requests)))
        (is (every? #(= "POST" (:method %)) @requests))
        (is (< (count (:body (last @requests))) 4096))
        (s/click! driver "button[data-region-mode=facets]")
        (editor/input! driver "#region-stroke input[name=radius]" "2" "input")
        (apply brush/stroke! driver (s/js driver "() => {const c=document.querySelector('canvas').getBoundingClientRect();return [c.x+c.width/2,c.y+c.height/2];}"))
        (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
        (is (= 5 (:revision (regions))))
        (is (= "indices" (:encoding (transport/decode (:body (last @requests))))))
        (s/go! driver (s/base-url sys))
        (s/await-part driver id)
        (is (s/wait-until #(= {:faces (count (:faces (regions))) :vertex-colors true} (:region-preview (s/stats driver))))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest whole-part-layer-and-display-toggle
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids)
        regions #(catalog/part-regions (catalog/part (catalog/snapshot! cat) id))]
    (try
      (s/go! driver (s/base-url sys))
      (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
      (s/wait-visible! driver ".detail--ready")
      (s/await-part driver id)
      (is (= "View: Mount faces" (s/text driver "#mount-colors-toggle")))
      (s/click! driver "[data-detail-tab=regions]")
      (s/click! driver "button[data-region-layer='Secondary']")
      (let [sidecar-before (persisted/authored! (:shipyard.catalog/db (:system started)) id)]
        (s/click! driver "button:text-is('Apply layer to entire part')")
        (is (s/wait-until #(= 12 (count (:faces (regions))))))
        (is (= "View: Layer types" (s/text driver "#mount-colors-toggle")))
        (is (= #{"Secondary"} (set (vals (:faces (regions))))))
        (is (s/wait-until #(= {:faces 12 :vertex-colors true} (:region-preview (s/stats driver)))))
        (is (= sidecar-before (dissoc (persisted/authored! (:shipyard.catalog/db (:system started)) id) :part/paint-regions))))
      (let [saved (regions)]
        (s/click! driver "#mount-colors-toggle")
        (is (s/wait-until #(true? (:mount-colors-enabled (s/stats driver)))))
        (is (false? (get-in (s/stats driver) [:region-preview :vertex-colors])))
        (is (= saved (regions)))
        (workspace/switch! driver "assembly")
        (is (= "Mount colors" (s/text driver "#mount-colors-toggle")))
        (workspace/switch! driver "browse")
        (s/await-part driver id)
        (is (= "View: Mount faces" (s/text driver "#mount-colors-toggle")))
        (let [interfaces (:interfaces (s/stats driver))]
          (is (nil? (:error interfaces)))
          (is (true? (:visible interfaces)))
          (is (= 2 (:count interfaces)))
          (is (= [2 2] (mapv :triangles (:items interfaces)))))
        (s/click! driver "[data-detail-tab=mounts]")
        (is (s/wait-until #(= id (get-in (s/stats driver) [:authoring :part-id]))))
        (apply s/click-point! driver (region-point driver 0))
        (is (s/wait-until #(= 2 (get-in (s/stats driver) [:preview :triangles])))
            "Painted parts still support mount face previews")
        (s/click! driver "[data-detail-tab=part]")
        (is (= saved (regions)))
        (s/click! driver "#mount-colors-toggle")
        (is (s/wait-until #(= {:faces 12 :vertex-colors true} (:region-preview (s/stats driver))))))
      (s/click! driver "[data-detail-tab=regions]")
      (s/click! driver "button[data-region-layer='Primary']")
      (s/click! driver "button:text-is('Apply layer to entire part')")
      (is (s/wait-until #(= 2 (:revision (regions)))))
      (is (empty? (:faces (regions))))
      (is (nil? (catalog/part-regions (catalog/part (catalog/snapshot! cat) (:weapon-alt fixture/ids)))))
      (s/go! driver (s/base-url sys))
      (s/wait-visible! driver ".detail--ready")
      (s/await-part driver id)
      (is (= "View: Layer types" (s/text driver "#mount-colors-toggle")))
      (is (= (regions) (:part/paint-regions (persisted/authored! (:shipyard.catalog/db (:system started)) id))))
      (s/click! driver "[data-detail-tab=regions]")
      (s/screenshot-el! driver "body" (java.io.File. "/tmp/shipyard-layer-fill.png"))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest angle-tolerance-paints-and-erases-a-curved-surface
  (s/assert-bundle!)
  (let [id (:weapon fixture/ids)
        started (fixture/start!
                 true (fn [root]
                        (fixture/library! root)
                        (with-open [out (io/output-stream (fs/file root id "unsupported.stl"))]
                          (.write out ^bytes (fixtures/->binary-stl (fixtures/uv-sphere 1 4 8))))
                        root))
        sys (:system started) driver (s/make-driver) cat (:shipyard.catalog/db sys)
        regions #(catalog/part-regions (catalog/part (catalog/snapshot! cat) id))]
    (try
      (s/go! driver (s/base-url sys))
      (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
      (s/await-part driver id)
      (s/click! driver "[data-detail-tab=regions]")
      (is (s/wait-until #(false? (:mount-colors-enabled (s/stats driver)))))
      (is (true? (s/js driver "() => document.querySelector('#region-angle-control').hidden")))
      (s/click! driver "button[data-region-mode=faces]")
      (s/wait-visible! driver "#region-angle")
      (editor/input! driver "#region-stroke input[name=radius]" "2" "input")
      (editor/input! driver "#region-angle" "0" "input")
      (apply brush/stroke! driver (region-point driver 0))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (is (< 0 (count (:faces (regions))) 48))
      (editor/input! driver "#region-angle" "60" "input")
      (apply brush/stroke! driver (region-point driver 0))
      (is (s/wait-until #(= 48 (count (:faces (regions))))))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (is (= "60" (s/js driver "() => document.querySelector('#region-angle').value")))
      (apply brush/right-stroke! driver (region-point driver 0))
      (is (s/wait-until #(empty? (:faces (regions)))))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (s/fill-and-blur! driver "#region-add input[name=name]" "Curved panels")
      (s/click! driver "button:text-is('Add layer')")
      (s/wait-visible! driver "button[aria-label='Rename Curved panels']")
      (is (= "60" (s/js driver "() => document.querySelector('#region-angle').value")))
      (s/click! driver "button[data-region-mode=facets]")
      (is (true? (s/js driver "() => document.querySelector('#region-angle-control').hidden")))
      (s/click! driver "button[data-region-mode=faces]")
      (editor/input! driver "#region-angle" "0" "input")
      (apply brush/stroke! driver (region-point driver 0))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (is (< 0 (count (:faces (regions))) 48) "Lowering tolerance recomputes the cached surface groups")
      (s/screenshot-el! driver "body" (java.io.File. "/tmp/shipyard-angle-tolerance.png"))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest strokes-before-htmx-settle-stay-in-the-workspace
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids)
        regions #(catalog/part-regions (catalog/part (catalog/snapshot! cat) id))]
    (try
      (s/go! driver (s/base-url sys))
      (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
      (s/await-part driver id)
      (s/click! driver "[data-detail-tab=regions]")
      (editor/input! driver "#region-stroke input[name=radius]" "2" "input")
      (let [point (region-point driver 0)]
        ;; Widen HTMX's normal swap/initialization gap deterministically.
        (s/js driver "() => { htmx.config.defaultSettleDelay = 10000; window.regionSettled = false; document.addEventListener('htmx:afterSettle', () => window.regionSettled = true, {once:true}); }")
        (apply brush/stroke! driver point)
        (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
        (is (false? (s/js driver "() => window.regionSettled")))
        (apply brush/stroke! driver point)
        (is (s/wait-until #(do (s/js driver "() => document.readyState") (= 2 (:revision (regions))))))
        (is (= (str (s/base-url sys) "/") (.url ^Page (:page driver))) "A second stroke during settle must never submit a document navigation")
        (is (false? (s/js driver "() => window.regionSettled")))
        (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
        (let [saved (regions) colors (region-colors driver)]
          (s/js driver "() => { window.savedHtmx = window.htmx; window.htmx = undefined; }")
          (apply brush/right-stroke! driver point)
          (is (= "Region save failed. Reopen this part or retry the stroke." (s/text driver "#region-status")))
          (is (= saved (regions)))
          (is (= colors (region-colors driver)) "Failed transport restores the saved preview")
          (is (= (str (s/base-url sys) "/") (.url ^Page (:page driver))))
          (s/js driver "() => { window.htmx = window.savedHtmx; }")
          (apply brush/right-stroke! driver point)
          (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
          (is (= 3 (:revision (regions))))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest rejected-region-requests-restore-the-brush
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids)
        regions #(catalog/part-regions (catalog/part (catalog/snapshot! cat) id))
        open! (fn []
                (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
                (s/await-part driver id)
                (s/click! driver "[data-detail-tab=regions]")
                (editor/input! driver "#region-stroke input[name=radius]" "2" "input"))]
    (try
      (s/go! driver (s/base-url sys))
      (open!)
      (apply brush/stroke! driver (region-point driver 0))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (s/js driver "() => document.addEventListener('htmx:afterRequest', e => {if(e.detail.xhr.status === 400) window.rejectedRegionResponse = {status:e.detail.xhr.status, body:e.detail.xhr.responseText};})")
      (doseq [[kind message] [[:wrong-endpoint "Invalid request (action, mesh-key, part-id, revision)"]
                              [:form "Invalid request (revision)"]
                              [:stroke "Invalid request (metadata)"]]]
        (let [saved (regions) colors (region-colors driver)]
          (s/js driver "() => {window.rejectedRegionResponse = null;}")
          (case kind
            :wrong-endpoint (s/js driver "() => {const form=document.querySelector('#region-stroke'); form.setAttribute('hx-post','/parts/regions'); form.setAttribute('action','/parts/regions');}")
            :form (s/js driver "() => {document.querySelector('#region-fill input[name=revision]').value='invalid';}")
            :stroke (s/js driver "() => {document.querySelector('#region-stroke input[name=revision]').value='invalid';}"))
          (if (= kind :form)
            (s/click! driver "#region-fill button")
            (apply brush/right-stroke! driver (region-point driver 0)))
          (is (s/wait-until #(= 400 (:status (s/js driver "() => window.rejectedRegionResponse")))))
          (is (.contains (s/text driver "#region-status") message))
          (is (.contains ^String (:body (s/js driver "() => window.rejectedRegionResponse")) message))
          (is (= saved (regions)))
          (is (= colors (region-colors driver)) "Rejected strokes restore the saved preview")
          (is (false? (s/js driver "() => document.querySelector('#region-stroke input[name=radius]').disabled")))
          (is (= (str (s/base-url sys) "/") (.url ^Page (:page driver))))
          ;; Fetch a fresh form through the supported UI, then paint again.
          (open!)
          (apply brush/stroke! driver (region-point driver 0))
          (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
          (is (= (inc (or (:revision saved) 0)) (:revision (regions))))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest database-failure-restores-the-brush
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids)
        regions #(catalog/part-regions (catalog/part (catalog/snapshot! cat) id))]
    (try
      (s/go! driver (s/base-url sys))
      (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
      (s/await-part driver id)
      (s/click! driver "[data-detail-tab=regions]")
      (editor/input! driver "#region-stroke input[name=radius]" "2" "input")
      (apply brush/stroke! driver (region-point driver 0))
      (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
      (let [saved (regions) colors (region-colors driver)]
        (with-redefs [catalog/save-regions! (fn [& _] (throw (ex-info "MDB_PROBLEM: txn should abort" {})))]
          (apply brush/right-stroke! driver (region-point driver 0))
          (is (s/wait-until #(= "Could not save part regions to the database. See the server log for details."
                               (s/text driver "#part-regions [role=alert]"))))
          (is (s/wait-until #(= "Region save failed. Reopen this part or retry the stroke."
                               (s/text driver "#region-status")))))
        (is (= saved (regions)))
        (is (= colors (region-colors driver)))
        (is (false? (s/js driver "() => document.querySelector('#region-stroke input[name=radius]').disabled")))
        (apply brush/right-stroke! driver (region-point driver 0))
        (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
        (is (= (inc (:revision saved)) (:revision (regions))))
        (is (empty? (:faces (regions)))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest repeated-strokes-reuse-picking-but-view-changes-invalidate-it
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        cat (:shipyard.catalog/db sys) id (:weapon fixture/ids)
        regions #(catalog/part-regions (catalog/part (catalog/snapshot! cat) id))
        captures #(s/js driver "() => window.regionReadbacks")
        paint! (fn []
                 (apply brush/stroke! driver (region-point driver 0))
                 (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status")))))]
    (try
      (s/go! driver (s/base-url sys))
      (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
      (s/await-part driver id)
      (s/click! driver "[data-detail-tab=regions]")
      (editor/input! driver "#region-stroke input[name=radius]" "2" "input")
      ;; Count real GPU reads without replacing the picker or its output.
      (s/js driver "() => {const gl=document.querySelector('canvas').getContext('webgl2'); const read=gl.readPixels.bind(gl); window.regionReadbacks=0; gl.readPixels=(...args)=>{window.regionReadbacks++; return read(...args);};}")
      (let [interfaces (:interfaces (s/stats driver))]
        (paint!)
        (is (= 1 (captures)))
        (is (= interfaces (:interfaces (s/stats driver))) "Painting preserves existing mount highlight geometry")
        (apply brush/right-stroke! driver (region-point driver 0))
        (is (s/wait-until #(= "Regions saved." (s/text driver "#region-status"))))
        (is (empty? (:faces (regions))))
        (is (= 1 (captures)) "Erasing after a save reuses picking despite nonindexed rendering")
        (s/click! driver "button[data-region-mode=faces]")
        (editor/input! driver "#region-stroke input[name=radius]" "3" "input")
        (paint!)
        (is (= 2 (count (:faces (regions)))))
        (is (= 1 (captures)) "Mode and radius changes sample the existing view")
        (is (= interfaces (:interfaces (s/stats driver)))))
      (let [[x y] (region-point driver 0) camera (:camera (s/stats driver))
            keyboard (.keyboard ^Page (:page driver))]
        (.down keyboard "Alt")
        (s/drag! driver [x y] [(+ x 70) (+ y 30)])
        (.up keyboard "Alt")
        (is (s/wait-until #(not= camera (:camera (s/stats driver)))))
        (paint!)
        (is (= 2 (captures)) "Orbiting requires a new visibility capture"))
      (s/resize! driver 1180 800)
      (s/await-rendered-geometries driver)
      (paint!)
      (is (= 3 (captures)) "Resizing invalidates the screen-space buffer")
      (s/click! driver ".part__select:has(.part__name:text-is('weapon-alt'))")
      (s/await-part driver (:weapon-alt fixture/ids))
      (s/click! driver "[data-detail-tab=regions]")
      (paint!)
      (is (= 4 (captures)) "A different source mesh cannot reuse another part's picking data")
      (is (seq (:faces (catalog/part-regions (catalog/part (catalog/snapshot! cat) (:weapon-alt fixture/ids))))))
      (finally (s/quit! driver) (fixture/stop! started)))))
