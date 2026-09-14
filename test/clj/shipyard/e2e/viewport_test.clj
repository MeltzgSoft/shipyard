(ns shipyard.e2e.viewport-test
  "Playwright's Chromium against a real server (TECHNICAL.md §10.3).

  Assertions read `window.__shipyard.stats()` rather than pixels: screenshot
  diffing a 3D scene moves with the driver, the antialiasing and the timing.
  One test still asks the crudest pixel question - is the canvas blank - because
  the stats hook would happily report a mesh that never reached the screen."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [shipyard.e2e.support :as s])
  (:import [javax.imageio ImageIO]))

(def ^:dynamic *driver* nil)
(def ^:dynamic *system* nil)

(use-fixtures :once
  (fn [run]
    (s/assert-bundle!)
    (let [system (s/start-system!)
          driver (s/make-driver)]
      (try
        (binding [*system* system, *driver* driver]
          (run))
        (finally
          (s/quit! driver)
          (ig/halt! system))))))

(defn- open-app! []
  (s/go! *driver* (s/base-url *system*))
  (s/wait-visible! *driver* "#library-results .part"))

(defn- select-part! [part-name]
  ;; Playwright reads a leading `//` as XPath, so this is the same selector it
  ;; always was - the button whose name span holds this text.
  (s/click! *driver* (format "//button[.//span[text()='%s']]" part-name)))

(defn- viewport-center []
  (s/js *driver*
        "() => { const r = document.getElementById('viewport').getBoundingClientRect();
                 return {x: r.left + r.width / 2, y: r.top + r.height / 2}; }"))

(defn- detail-layout []
  (s/js *driver*
        "() => {
           const stage = document.querySelector('.stage');
           const detail = document.getElementById('detail');
           const child = detail.firstElementChild;
           const style = getComputedStyle(detail);
           const stageRect = stage.getBoundingClientRect();
           const detailRect = detail.getBoundingClientRect();
           const childRect = child ? child.getBoundingClientRect() : null;
           const detailInnerHeight = detail.clientHeight
             - parseFloat(style.paddingTop)
             - parseFloat(style.paddingBottom);

           return {
             childClass: child ? child.className : null,
             childFills: childRect ? childRect.height >= detailInnerHeight - 2 : false,
             childHeight: childRect ? childRect.height : null,
             detailHeight: detailRect.height,
             detailInnerHeight,
             detailShare: detailRect.height / stageRect.height,
             stageHeight: stageRect.height
           };
         }"))

(defn- assert-detail-panel-fits-content! [state-label]
  (let [layout (detail-layout)]
    (is (< (:detailHeight layout) (:stageHeight layout))
        (str state-label " detail panel should float within the stage; layout was "
             (pr-str layout)))
    (is (<= (:detailHeight layout) (+ (:childHeight layout) 28))
        (str state-label " detail panel should fit its content rather than forcing full height; layout was "
             (pr-str layout)))
    (is (:childFills layout)
        (str state-label " detail content should fill its panel; layout was "
             (pr-str layout)))))

(defn- close? [a b]
  (< (abs (- (double a) (double b))) 0.08))

(defn- vec-close? [got want]
  (every? (fn [[a b]] (close? a b)) (map vector got want)))

(defn- await-preview
  ([] (await-preview -1))
  ([after-revision]
   (s/wait-until
    #(let [p (:preview (s/stats *driver*))]
       (when (and p (> (:revision p) after-revision)) p)))))

(defn- await-stable-geometries
  "Wait for Three's renderer accounting to observe the current scene.

  A mesh becomes selectable before the next render accounts for all of its
  auxiliary orientation-guide geometry.  Comparing a later rendered count to
  that early value mistakes the initial accounting catch-up for a leak."
  []
  (let [previous (atom nil)]
    (s/wait-until
     #(let [current (:geometries (s/stats *driver*))]
        (if (= current @previous)
          current
          (do (reset! previous current) nil))))))

(defn- enter-authoring! [part-id]
  (when-not (= part-id (get-in (s/stats *driver*) [:authoring :part-id]))
    (s/click! *driver* "[data-authoring-toggle]"))
  (s/wait-until #(= part-id (get-in (s/stats *driver*) [:authoring :part-id]))))

;; --- WebGL first, per the acceptance criteria -------------------------------

(deftest webgl-works-in-this-browser
  (testing "asserted before anything else: a GPU-less runner without software
            rendering fails in a way that looks like an application bug"
    (open-app!)
    (let [ctx (s/js *driver*
                    "() => { const c = document.getElementById('viewport');
                             const gl = c.getContext('webgl2') || c.getContext('webgl');
                             return gl ? gl.getParameter(gl.VERSION) : null; }")]
      (is (some? ctx) "no WebGL context - is SwiftShader enabled?")
      (is (str/includes? (str ctx) "WebGL")))
    (testing "and the island came up rather than falling into degraded mode"
      (is (some? (s/wait-until #(s/stats *driver*)))))))

;; --- browsing ---------------------------------------------------------------

(deftest browse-and-filter
  (open-app!)
  (is (= 5 (s/count-els *driver* "#library-results .part")))
  (testing "a supported-only part is greyed, with its reason, not hidden"
    (is (= 1 (s/count-els *driver* ".part--unrenderable")))
    (is (str/includes? (s/text *driver* ".part--unrenderable")
                       "supported STL")))
  (testing "filtering by bundle narrows the list"
    (s/select-option! *driver* "select[name=bundle]" "Ork Fleet Bundle")
    (is (s/wait-until #(= 1 (s/count-els *driver* "#library-results .part"))))
    (is (str/includes? (s/text *driver* "#library-results") "Ram Ship")))
  (testing "and All bundles widens it again"
    (s/select-option! *driver* "select[name=bundle]" "All bundles")
    (is (s/wait-until #(= 5 (s/count-els *driver* "#library-results .part")))))
  (testing "free-text search matches names across bundles"
    (s/fill! *driver* "input[name=q]" "Ram")
    (is (s/wait-until #(= 2 (s/count-els *driver* "#library-results .part"))))))

(deftest browser-results-scroll-within-the-library-panel
  (try
    (s/resize! *driver* 1280 360)
    (open-app!)
    (s/scroll-into-view! *driver* "#library-results .part:last-child")
    (let [library (s/bounds *driver* "#library")
          last-part (s/bounds *driver* "#library-results .part:last-child")]
      (is (>= (:y last-part) (:y library))
          (str "the final result should not scroll above the library: " {:library library :last-part last-part}))
      (is (<= (+ (:y last-part) (:height last-part))
              (+ (:y library) (:height library)))
          (str "the final result should remain visible inside the library: " {:library library :last-part last-part})))
    (finally
      (s/resize! *driver* 1280 900))))

(deftest bulk-orientation-renders-rotates-and-saves-a-selection
  (open-app!)
  (s/click! *driver* ".masthead__mode[href='/orient']")
  (s/wait-visible! *driver* "#bulk-orient-filters")
  (is (s/wait-until #(= 5 (s/count-els *driver* "[data-bulk-select]")))
      "the orientation table should finish its initial HTMX load")
  (is (> (s/width *driver* "#library.bulk-orient") 1000)
      "the selection table should own the workspace rather than stay in the browse rail")
  (s/check! *driver* (str "[data-bulk-select][value='" s/hull-id "']"))
  (s/check! *driver* (str "[data-bulk-select][value='" s/prow-id "']"))
  (is (= "2 selected" (s/text *driver* "[data-bulk-count]")))
  (s/click! *driver* "[data-bulk-render-button]")
  (s/wait-visible! *driver* "[data-bulk-grid]")
  (is (> (s/width *driver* "[data-bulk-grid]") 1000)
      "the rendered grid should replace the table at workspace width")
  (let [loaded (s/wait-until #(let [bulk (:bulk (s/stats *driver*))]
                                (when (= 2 (:count bulk)) bulk)))]
    (is (some? loaded) "both selected meshes should reach the grid"))
  (s/click! *driver* "[data-bulk-step='15']")
  (s/click! *driver* "[data-bulk-rotate][data-axis='y'][data-direction='1']")
  (is (s/wait-until #(= 2 (get-in (s/stats *driver*) [:bulk :dirty])))
      "one toolbar action should update every selected mesh")
  (s/fill-and-blur! *driver* "[data-bulk-angle][data-axis='y']" "90")
  (is (s/wait-until #(every? (fn [q] (vec-close? q [0.0 0.7071 0.0 0.7071]))
                             (vals (get-in (s/stats *driver*) [:bulk :orientations]))))
      "a manual yaw value should set every selected mesh to that absolute angle")
  (s/click! *driver* "[data-bulk-save] button[type='submit']")
  (is (s/wait-until #(str/includes? (s/text *driver* "#bulk-orient-status")
                                    "Saved 2 orientations")))
  (is (zero? (get-in (s/stats *driver*) [:bulk :dirty]))
      "a successful response should establish a new saved baseline")
  ;; Restore the fixture sidecars so the once-scoped server remains independent
  ;; of randomized test order.
  (s/fill-and-blur! *driver* "[data-bulk-angle][data-axis='y']" "0")
  (s/click! *driver* "[data-bulk-save] button[type='submit']")
  (is (s/wait-until #(zero? (get-in (s/stats *driver*) [:bulk :dirty]))))
  (s/click! *driver* "[data-bulk-back]")
  (is (zero? (s/count-els *driver* "[data-bulk-grid]")))
  (is (= "2 selected" (s/text *driver* "[data-bulk-count]"))
      "returning to the table should preserve the working selection")
  (is (zero? (get-in (s/stats *driver*) [:bulk :count]))
      "returning to the table should release its mesh grid"))

(defn- assert-bulk-preview-pixels! [part-id]
  (let [selector (str "[data-bulk-part='" part-id "'] [data-bulk-preview]")
        shot (io/file (System/getProperty "java.io.tmpdir")
                      (str "shipyard-bulk-preview-" (last (str/split part-id #"/")) ".png"))]
    (s/scroll-into-view! *driver* selector)
    ;; These rectangles contain only the model, without labels or borders.
    ;; A pixel assertion here catches a shared global scene even if its stats
    ;; truthfully report that all the selected meshes have loaded.
    (is (s/wait-until
         #(do
            (s/screenshot-el! *driver* selector shot)
            (let [img (ImageIO/read shot)
                  w (.getWidth img)
                  h (.getHeight img)
                  bright (for [x (range 2 (- w 2) 2)
                               y (range 2 (- h 2) 2)
                               :let [rgb (.getRGB img x y)]
                               :when (every? (fn [shift] (> (bit-and 255 (bit-shift-right rgb shift)) 85))
                                             [0 8 16])]
                           [x y])]
              (and (> (count bright) 40)
                   (every? (fn [[x y]] (and (< 4 x (- w 5)) (< 4 y (- h 5)))) bright))))
         10000)
        (str "the model should render and fit inside its own preview: " part-id))))

(deftest bulk-models-render-inside-their-cards
  (open-app!)
  (s/click! *driver* ".masthead__mode[href='/orient']")
  (s/wait-visible! *driver* "[data-bulk-select]")
  (doseq [id [s/hull-id s/prow-id s/mount-plate-id s/ork-id]]
    (s/check! *driver* (str "[data-bulk-select][value='" id "']")))
  (s/click! *driver* "[data-bulk-render-button]")
  (is (s/wait-until #(= 4 (get-in (s/stats *driver*) [:bulk :count]))))
  (doseq [id [s/hull-id s/prow-id s/mount-plate-id s/ork-id]]
    (assert-bulk-preview-pixels! id))
  (s/click! *driver* "[data-bulk-rotate][data-axis='y'][data-direction='1']")
  (assert-bulk-preview-pixels! s/prow-id)
  (try
    (s/resize! *driver* 420 450)
    (assert-bulk-preview-pixels! s/ork-id)
    (is (pos? (s/js *driver* "() => document.querySelector('.bulk-grid__cards').scrollTop"))
        "the small grid should scroll to reveal its last model")
    (assert-bulk-preview-pixels! s/prow-id)
    (finally
      (s/resize! *driver* 1280 900))))

;; --- loading ----------------------------------------------------------------

(deftest loading-a-part-renders-and-frames-it
  (open-app!)
  (select-part! "Cruiser Hull")
  (let [stats (s/await-part *driver* s/hull-id)]
    (is (pos? (:vertices stats)))
    (is (pos? (:triangles stats)))
    ;; Polled, not sampled. `:draws` is `renderer.info.render.calls`, which
    ;; reports the *last frame*, and `await-part` returns as soon as the mesh is
    ;; in the scene - which can precede the first frame that draws it. A single
    ;; read is a race the old driver happened to win.
    (is (s/wait-until #(pos? (:draws (s/stats *driver*))))
        "the scene is actually being rendered")
    (testing "and it is a PBR material, because M5 depends on it"
      (is (seq (:materials stats)))))
  (testing "the camera frames the part from the header bbox"
    ;; The prow fixture is the one solid that is not centred on the origin, so
    ;; this fails for a camera that never moved rather than passing by accident.
    (select-part! "Classic Ram Prow")
    (let [stats  (s/await-part *driver* s/prow-id)
          target (:target stats)]
      (is (every? (fn [[got want]] (< (abs (- (double got) want)) 0.25))
                  (map vector target s/prow-offset))
          (str "camera target " (pr-str target) " should be near " (pr-str s/prow-offset))))))

(deftest part-orientation-previews-persists-and-resets
  (open-app!)
  (select-part! "Mount Test Plate")
  (s/await-part *driver* s/mount-plate-id)
  (is (vec-close? (:orientation (s/stats *driver*)) [0.0 0.0 0.0 1.0]))
  (let [guide (:orientation-guide (s/stats *driver*))]
    (is (:wireframe? guide) "a loaded part should have an orientation wireframe")
    (is (= "top-right" (:location guide))
        "the guide should stay in a fixed viewport corner")
    (is (= ["x" "y" "z"] (:positive-rotation-arcs guide))
        "each axis should show its positive rotational direction")
    (is (= [{:axis "x" :direction [1 0 0] :color-css "#ff5c5c"}
            {:axis "y" :direction [0 1 0] :color-css "#5ce080"}
            {:axis "z" :direction [0 0 1] :color-css "#57a7ff"}]
           (:axes guide))
        "the guide should label fixed canonical X, Y, and Z directions"))
  (s/js *driver* "() => {
    const form = document.querySelector('.part-orientation__form');
    const setAngle = (name, value) => {
      form.querySelector(`input[name=${name}]`).value = value;
    };
    setAngle('part-yaw-deg', '0');
    setAngle('part-pitch-deg', '90');
    setAngle('part-roll-deg', '0');
    form.querySelector('input[name=part-pitch-deg]').dispatchEvent(new Event('input', {bubbles: true}));
  }")
  (is (s/wait-until
       #(vec-close? (:orientation (s/stats *driver*))
                    [0.7071068 0.0 0.0 0.7071068]))
      "pitch should preview as a rotation around X")
  (s/js *driver* "() => {
    const form = document.querySelector('.part-orientation__form');
    form.querySelector('input[name=part-pitch-deg]').value = '0';
    form.querySelector('input[name=part-pitch-deg]').dispatchEvent(new Event('input', {bubbles: true}));
  }")
  (s/js *driver* "() => {
    const form = document.querySelector('.part-orientation__form');
    form.querySelector('input[name=part-roll-deg]').value = '90';
    form.querySelector('input[name=part-roll-deg]').dispatchEvent(new Event('input', {bubbles: true}));
  }")
  (is (s/wait-until
       #(vec-close? (:orientation (s/stats *driver*))
                    [0.0 0.0 0.7071068 0.7071068]))
      "roll should preview as a rotation around Z")
  (s/js *driver* "() => {
    const form = document.querySelector('.part-orientation__form');
    form.querySelector('input[name=part-yaw-deg]').value = '-90';
    form.querySelector('input[name=part-yaw-deg]').dispatchEvent(new Event('input', {bubbles: true}));
  }")
  (is (s/wait-until
       #(vec-close? (:orientation (s/stats *driver*))
                    [-0.5 -0.5 0.5 0.5]))
      "yaw stays on the widget's canonical Y axis after roll")
  (let [guide (:orientation-guide (s/stats *driver*))]
    (is (vec-close? (:orientation guide) [-0.5 -0.5 0.5 0.5])
        "the corner wireframe should preview the same orientation as the solid mesh"))
  (s/click! *driver* ".part-orientation__actions button[value=save]")
  (is (s/wait-until
       #(vec-close? (get-in (s/stats *driver*) [:orientation-guide :orientation])
                    [0.0 0.0 0.0 1.0]))
      "saving should make the current pose the wireframe's standard orientation")
  (is (vec-close? (:orientation (s/stats *driver*))
                  [-0.5 -0.5 0.5 0.5])
      "saving should leave the solid mesh in its canonical pose")
  (select-part! "Classic Ram Prow")
  (s/await-part *driver* s/prow-id)
  (select-part! "Mount Test Plate")
  (s/await-part *driver* s/mount-plate-id)
  (is (vec-close? (:orientation (s/stats *driver*))
                  [-0.5 -0.5 0.5 0.5])
      "loading the part again should restore its sidecar orientation")
  (is (vec-close? (get-in (s/stats *driver*) [:orientation-guide :orientation])
                  [0.0 0.0 0.0 1.0])
      "a loaded saved orientation should be the wireframe's standard")
  (s/click! *driver* ".part-orientation__actions button[value=reset]")
  (is (s/wait-until
       #(vec-close? (:orientation (s/stats *driver*)) [0.0 0.0 0.0 1.0]))
      "Reset should restore source orientation"))

(deftest selecting-an-unpreviewable-part-clears-the-scene
  ;; The other half of the selection story: `show-only!` decides what replaces
  ;; what, `shipyard:clear` is what empties the scene when there is nothing to
  ;; show at all.
  (open-app!)
  (select-part! "Cruiser Hull")
  (s/await-part *driver* s/hull-id)
  (select-part! "Supported Only Prow")
  (is (s/wait-until #(empty? (s/loaded-parts *driver*)))
      "shipyard:clear should have emptied the scene")
  (testing "and the panel says why rather than going blank"
    (is (str/includes? (s/text *driver* "#detail") "supported STL"))))

(deftest detail-panel-views-fit-the-floating-inspector
  (open-app!)
  (testing "empty detail"
    (assert-detail-panel-fits-content! "empty"))
  (testing "loaded detail"
    (select-part! "Cruiser Hull")
    (s/await-part *driver* s/hull-id)
    (assert-detail-panel-fits-content! "loaded"))
  (testing "unrenderable detail"
    (select-part! "Supported Only Prow")
    (is (s/wait-until #(str/includes? (s/text *driver* "#detail") "supported STL")))
    (assert-detail-panel-fits-content! "unrenderable")))

(deftest selecting-another-part-replaces-the-first
  (testing "M1 is a single-part viewer (SPEC §10): picking a part shows that part"
    (open-app!)
    (select-part! "Cruiser Hull")
    (is (= [s/hull-id] (vec (:parts (s/await-part *driver* s/hull-id)))))

    ;; **Both renderable.** That is the whole point: an unrenderable part fires
    ;; `shipyard:clear` and empties the scene as a side effect, which is how
    ;; #47 hid behind a green suite for a whole milestone.
    (select-part! "Classic Ram Prow")
    (let [stats (s/await-part *driver* s/prow-id)]
      (is (= [s/prow-id] (vec (:parts stats)))
          "the hull should be gone, not sitting behind the prow"))))

(deftest a-replaced-part-is-disposed-not-merely-removed
  (testing "`parts` is bookkeeping; it shrinks whether or not the GPU buffers
            were released. three's own geometry count is what tells them apart."
    (open-app!)
    (select-part! "Cruiser Hull")
    (s/await-part *driver* s/hull-id)
    (let [baseline (await-stable-geometries)]
      (is (pos? baseline) "the stats hook should be reporting live geometries")
      (dotimes [_ 4]
        (select-part! "Classic Ram Prow")
        (s/await-part *driver* s/prow-id)
        (select-part! "Cruiser Hull")
        (s/await-part *driver* s/hull-id))
      (s/await-part *driver* s/hull-id)
      (is (s/wait-until #(<= (:geometries (s/stats *driver*)) baseline))
          (str "geometries grew from " baseline " to "
               (:geometries (s/stats *driver*))
               " over four selection cycles - a replaced part was removed "
               "from the scene without being disposed")))))

;; --- mount authoring --------------------------------------------------------

(deftest mount-authoring-clicks-a-face-and-renders-a-preview
  (open-app!)
  (select-part! "Mount Test Plate")
  (s/await-part *driver* s/mount-plate-id)
  (is (enter-authoring! s/mount-plate-id)
      "authoring mode should be active for the loaded plate")
  (is (= "part"
         (s/js *driver* "() => document.querySelector('[data-detail-tab].detail__tab--active').dataset.detailTab"))
      "part details should be the default inspector tab")
  (let [{:keys [x y]} (viewport-center)
        _ (s/click-point! *driver* x y)
        first-preview (await-preview)]
    (is (= "mounts"
           (s/js *driver* "() => document.querySelector('[data-detail-tab].detail__tab--active').dataset.detailTab"))
        "selecting a face should reveal mount authoring")
    (is (true? (s/js *driver* "() => document.querySelector('[data-detail-panel=part]').hidden")))
    (is (false? (s/js *driver* "() => document.querySelector('[data-detail-panel=mounts]').hidden")))
    (is (= 2 (:triangles first-preview)))
    (is (vec-close? (:position first-preview) [2.0 1.0 0.0])
        (str "preview position was " (pr-str (:position first-preview))))
    (is (vec-close? (:axis first-preview) [0.0 0.0 1.0])
        (str "preview axis was " (pr-str (:axis first-preview))))
    (is (vec-close? (:roll first-preview) [1.0 0.0 0.0])
        (str "preview roll was " (pr-str (:roll first-preview))))
    (is (vec-close? (:up first-preview) [0.0 1.0 0.0])
        (str "preview up was " (pr-str (:up first-preview))))
    (is (= 7 (:geometries first-preview))
        "highlight and all three frame arrows should be observable")
    (testing "a new pick replaces the previous preview instead of growing GPU geometry"
      (let [baseline (:geometries (s/stats *driver*))
            revision (:revision first-preview)
            center (viewport-center)]
        (s/click-point! *driver* (:x center) (:y center))
        (is (some? (await-preview revision)))
        (is (<= (:geometries (s/stats *driver*)) baseline)
            "repeated picks should not leak Three.js geometries"))))
  (testing "part changes clear previews but retain global face picking"
    (select-part! "Cruiser Hull")
    (s/await-part *driver* s/hull-id)
    (is (s/wait-until #(nil? (:preview (s/stats *driver*))))
        "the old facet preview should not survive a part change")
    (is (s/wait-until #(= s/hull-id (get-in (s/stats *driver*) [:authoring :part-id])))
        "face picking should follow the newly loaded part")
    (is (= "true"
           (s/js *driver* "() => document.querySelector('[data-authoring-toggle]').getAttribute('aria-pressed')"))
        "the viewport control should show that global face picking is active")
    (is (= true
           (s/js *driver* "() => {
             const button = document.querySelector('[data-authoring-toggle]');
             return button.closest('.stage') !== null && !document.querySelector('#detail [data-authoring-toggle]');
           }"))
        "the global mode control belongs to the viewport, not the changing detail panel")
    (s/click! *driver* "[data-authoring-toggle]")
    (is (s/wait-until #(nil? (:authoring (s/stats *driver*))))
        "Done picking should disable the global mode")))

(deftest orbit-controls-work-outside-authoring-mode
  (open-app!)
  (select-part! "Mount Test Plate")
  (s/await-part *driver* s/mount-plate-id)
  (let [{:keys [x y]} (viewport-center)
        before-stats (s/stats *driver*)
        before (:camera before-stats)
        before-guide-camera (get-in before-stats [:orientation-guide :camera-orientation])]
    (s/drag! *driver* [x y] [(+ x 160) (+ y 30)])
    (is (s/wait-until #(not (vec-close? before (:camera (s/stats *driver*)))))
        "dragging the canvas should still orbit when authoring is inactive")
    (is (s/wait-until
         #(let [{:keys [camera-orientation viewer-camera-orientation]}
                (:orientation-guide (s/stats *driver*))]
            (when (and (not (vec-close? before-guide-camera camera-orientation))
                       (vec-close? camera-orientation viewer-camera-orientation))
              true)))
        "the corner widget should match the model camera while orbiting")
    (is (nil? (:preview (s/stats *driver*)))
        "ordinary orbiting should not create a facet preview")))

(deftest mount-wizard-saves-and-deletes-a-preview
  (open-app!)
  (select-part! "Mount Test Plate")
  (s/await-part *driver* s/mount-plate-id)
  (enter-authoring! s/mount-plate-id)
  (let [{:keys [x y]} (viewport-center)]
    (s/click-point! *driver* x y))
  (is (some? (await-preview)))
  (let [layout (s/js *driver* "() => {
    const detail = document.getElementById('detail');
    const actions = document.querySelector('.mount-wizard__actions');
    const detailRect = detail.getBoundingClientRect();
    const actionRect = actions.getBoundingClientRect();
    return {
      fits: actionRect.bottom <= detailRect.bottom,
      detailHeight: detailRect.height,
      detailBottom: detailRect.bottom,
      actionsBottom: actionRect.bottom,
      scrollHeight: detail.scrollHeight,
      clientHeight: detail.clientHeight
    };
  }")]
    (is (:fits layout)
        (str "the mount wizard should use the detail panel width instead of "
             "hiding actions below the fold; layout was " (pr-str layout))))
  (is (s/wait-until
       #(false? (s/js *driver* "() => !!document.querySelector('.mount-wizard__form select[name=part-role]')")))
      "part role should be edited outside the mount wizard")
  (is (= "plug" (s/js *driver* "() => document.querySelector('.mount-wizard__form select[name=kind]').value")))
  (is (= "Geometry suggests plug. You can change this."
         (s/text *driver* ".mount-wizard__hint")))
  (is (= true
         (s/js *driver* "() => document.querySelector('.mount-wizard__roles').hidden"))
      "plug authoring must not show a socket acceptance profile")
  (s/select-option! *driver* ".mount-wizard__form select[name=kind]" "socket")
  (is (= "socket" (s/js *driver* "() => document.querySelector('.mount-wizard__form select[name=kind]').value"))
      "the geometry default must remain manually editable")
  (is (s/wait-until
       #(false? (s/js *driver* "() => document.querySelector('.mount-wizard__roles').hidden")))
      "socket controls appear immediately after changing Kind")
  (s/js *driver* "() => { const input = document.querySelector('.mount-wizard__form input[name=capacity]'); input.value = '2'; input.dispatchEvent(new Event('input', {bubbles: true})); }")
  (is (s/wait-until #(= 2 (count (get-in (s/stats *driver*) [:preview :split-centers]))))
      "vertical split previews both positions")
  (let [vertical (get-in (s/stats *driver*) [:preview :split-centers])]
    (s/select-option! *driver* ".mount-wizard__form select[name=split-direction]" "Horizontal — equal heights")
    (is (s/wait-until #(not= vertical (get-in (s/stats *driver*) [:preview :split-centers])))
        "horizontal split changes positions on the selected face")
    (is (= 1 (count (get-in (s/stats *driver*) [:preview :split-lines]))))
    (s/select-option! *driver* ".mount-wizard__form select[name=split-direction]" "Vertical — equal widths"))
  (s/click! *driver* ".mount-wizard__actions button[value=create]")
  (is (s/wait-until #(str/includes? (s/text *driver* "#detail") "weapon-1"))
      (str "the saved mount should appear in the detail panel; got "
           (pr-str (s/text *driver* "#detail"))))
  (is (str/includes? (s/text *driver* "#detail") "x2")
      "the saved socket capacity should appear in the detail panel")
  (is (str/includes? (s/text *driver* "#detail") "Interface colors")
      "configured interfaces should get a color legend")
  (let [interfaces (s/wait-until
                    #(let [interfaces (:interfaces (s/stats *driver*))]
                       (when (some (fn [item] (= "weapon-1" (:mount-id item)))
                                   (:items interfaces))
                         interfaces)))
        last-interfaces (:interfaces (s/stats *driver*))
        saved (filterv #(= "weapon-1" (:mount-id %)) (:items interfaces))]
    (is (= [{:type "weapon" :mount-id "weapon-1" :triangles 2 :candidates 2}]
           (mapv #(select-keys % [:type :mount-id :triangles :candidates]) saved)))
    (is (= 1 (count (:split-lines (first saved))))
        "the saved capacity split renders one persistent boundary")
    (is (= 2 (count (:split-centers (first saved))))
        (str "the saved capacity split keeps two rendered sections; interfaces were "
             (pr-str last-interfaces))))
  (is (s/wait-until #(nil? (:preview (s/stats *driver*))))
      "saving clears the transient preview")
  (s/click! *driver* "[data-detail-tab=mounts]")
  (s/click! *driver* "form:has(input[name=mount-id][value='weapon-1']) button:has-text('Edit')")
  (is (s/wait-until #(true? (s/js *driver* "() => !!document.querySelector('.mount-wizard__form button[value=update]')")))
      "Edit should open the mount with an update action")
  (let [edit-preview (await-preview)]
    (is (= 2 (:triangles edit-preview))
        "editing should recover and highlight the saved mount face")
    (let [revision (:revision edit-preview)
          {:keys [x y]} (viewport-center)]
      (s/click-point! *driver* x y)
      (is (some? (await-preview revision))
          "an edited mount should allow its face to be picked again")
      (is (s/wait-until #(true? (s/js *driver* "() => !!document.querySelector('.mount-wizard__form button[value=update]')")))
          "picking another face should preserve edit mode"))
    (s/js *driver* "() => {
      const input = document.querySelector('.mount-wizard__form input[name=twist-deg]');
      input.value = '90';
      input.dispatchEvent(new Event('input', {bubbles: true}));
    }")
    (is (s/wait-until #(vec-close? (:roll (:preview (s/stats *driver*))) [0.0 1.0 0.0]))
        "changing Twist should rotate the cyan +X arrow immediately")
    (is (s/wait-until #(vec-close? (:up (:preview (s/stats *driver*))) [-1.0 0.0 0.0]))
        "changing Twist should rotate the pink +Y arrow with it"))
  (s/js *driver* "() => { document.querySelector('.mount-wizard__form input[name=capacity]').value = '3'; }")
  (s/click! *driver* ".mount-wizard__actions button[value=update]")
  (is (s/wait-until #(str/includes? (s/text *driver* "#detail") "x3"))
      (str "saving an edit should update the existing mount; detail was "
           (pr-str (s/text *driver* "#detail"))))
  (is (enter-authoring! s/mount-plate-id)
      "authoring mode should be active before picking another face")
  (let [{:keys [x y]} (viewport-center)]
    (s/click-point! *driver* x y))
  (is (some? (await-preview)))
  (s/js *driver* "() => { document.querySelector('.mount-wizard__form input[name=mount-id]').value = 'weapon-1'; }")
  (s/click! *driver* ".mount-wizard__actions button[value=create]")
  (is (s/wait-until #(str/includes? (s/text *driver* "#detail") "already exists"))
      "duplicate ids should report the validation error in the detail panel")
  (is (s/wait-until #(true? (s/js *driver* "() => !!document.querySelector('.mount-wizard__form button[value=replace]')")))
      "the error state should keep the form available so Replace is reachable")
  (s/click! *driver* ".detail__dismiss")
  (is (s/wait-until #(and (str/includes? (s/text *driver* "#detail") "weapon-1")
                          (not (str/includes? (s/text *driver* "#detail") "already exists"))))
      "dismissing the error should restore the normal loaded detail")
  (s/select-option! *driver* ".part-metadata__form select[name=part-role]" "hull")
  (s/click! *driver* ".part-metadata__form button")
  (is (s/wait-until #(str/includes? (s/text *driver* "#detail") "Manual"))
      "part-level role edits should live in the metadata form")
  (s/select-option! *driver* "select[name=class]" "Cruiser")
  (is (s/wait-until #(= "hull" (s/js *driver* "() => {
    const card = [...document.querySelectorAll('.part')].find((el) => el.textContent.includes('Mount Test Plate'));
    return card ? card.querySelector('.part__role').textContent : null;
  }")))
      "the manual role is visible as the part's authoritative role")
  (s/click! *driver* "[data-detail-tab=mounts]")
  (s/click! *driver* "form:has(input[name=mount-id][value='weapon-1']) button:has-text('Delete')")
  (is (s/wait-until #(not (str/includes? (s/text *driver* "#detail") "weapon-1")))
      "deleting removes the mount from the detail panel")
  (is (s/wait-until #(not-any? (fn [item] (= "weapon-1" (:mount-id item)))
                               (get-in (s/stats *driver*) [:interfaces :items])))
      "deleting removes the configured interface highlight"))

(deftest mount-wizard-mirrors-and-repeats-a-socket-classification
  (open-app!)
  (select-part! "Mount Test Plate")
  (s/await-part *driver* s/mount-plate-id)
  (enter-authoring! s/mount-plate-id)
  (let [{:keys [x y]} (viewport-center)]
    (s/click-point! *driver* x y))
  (is (some? (await-preview)))
  (s/select-option! *driver* ".mount-wizard__form select[name=kind]" "socket")
  (is (= "SELECT" (s/js *driver* "() => document.querySelector('select[name=accepts]').tagName")))
  (let [layout (s/js *driver* "() => {
                               const select = document.querySelector('select[name=accepts]');
                               return {
                                 roles: [...select.options].map(option => option.value)
                               };
                             }")]
    (is (= (sort (:roles layout)) (:roles layout))
        (str "acceptance profiles should be alphabetized; layout was " (pr-str layout)))
    (is (every? (set (:roles layout)) ["weapon" "turret"])
        (str "socket profiles should retain the required weapon and turret choices; layout was "
             (pr-str layout))))
  (is (true?
       (s/js *driver* "() => {
           const form = document.querySelector('.mount-wizard__form');
           const mountId = form.querySelector('input[name=mount-id]').value;
           return mountId.startsWith('weapon-')
             && form.querySelector('input[name=mirror-id]').value === `${mountId}-mirror`
             && form.querySelector('input[name=capacity]').value === '1';
         }"))
      "a new face starts with the selected acceptance type and one section")
  (s/select-option! *driver* "select[name=accepts]" "weapon")
  (s/select-option! *driver* "select[name=accepts]" "turret")
  (is (= "turret" (s/js *driver* "() => document.querySelector('select[name=accepts]').value")))
  (is (true?
       (s/js *driver* "() => {
           const form = document.querySelector('.mount-wizard__form');
           const mountId = form.querySelector('input[name=mount-id]').value;
           return mountId.startsWith('turret-')
             && form.querySelector('input[name=mirror-id]').value === `${mountId}-mirror`;
         }"))
      "changing acceptance updates generated mount and mirror prefixes")
  (s/js *driver* "() => { document.querySelector('.mount-wizard__form input[name=capacity]').value = '2'; }")
  (s/click! *driver* "input[name=mirror]")
  (let [mirrored (s/wait-until
                  #(let [preview (:preview (s/stats *driver*))]
                     (when (:mirror-visible? preview) preview)))]
    (is (some? mirrored) "checking Mirror should show the reflected face before save")
    (is (vec-close? (:mirror-position mirrored) [-2.0 1.0 0.0])
        (str "mirrored preview position was " (pr-str (:mirror-position mirrored))))
    (is (vec-close? (:mirror-axis mirrored) [0.0 0.0 1.0])
        (str "mirrored preview axis was " (pr-str (:mirror-axis mirrored))))
    (is (vec-close? (:mirror-roll mirrored) [-1.0 0.0 0.0])
        (str "mirrored preview roll was " (pr-str (:mirror-roll mirrored)))))
  (s/click! *driver* "input[name=repeat]")
  (s/js *driver* "() => {
    const form = document.querySelector('.mount-wizard__form');
    form.querySelector('input[name=mount-id]').value = 'port-1';
    form.querySelector('input[name=mirror-id]').value = 'starboard-1';
  }")
  (s/click! *driver* ".mount-wizard__actions button[value=create]")
  (is (s/wait-until #(and (str/includes? (s/text *driver* "#detail") "port-1")
                          (str/includes? (s/text *driver* "#detail") "starboard-1")))
      (str "saving with mirror should persist both sockets; detail was "
           (pr-str (s/text *driver* "#detail"))))
  (is (s/wait-until
       #(let [items (get-in (s/stats *driver*) [:interfaces :items])
              mirrored (filter (fn [item]
                                 (contains? #{"port-1" "starboard-1"} (:mount-id item)))
                               items)]
          (and (= #{"port-1" "starboard-1"} (set (map :mount-id mirrored)))
               (every? pos? (map :triangles mirrored)))))
      "both saved faces are colored immediately, including the mirrored face")
  (is (s/wait-until #(= s/mount-plate-id (get-in (s/stats *driver*) [:authoring :part-id])))
      "repeat keeps face-picking active for the next socket")
  (let [{:keys [x y]} (viewport-center)]
    (s/click-point! *driver* x y))
  (is (some? (await-preview)))
  (is (s/wait-until
       #(= "port-2" (s/js *driver* "() => document.querySelector('.mount-wizard__form input[name=mount-id]').value"))))
  (is (= "plug" (s/js *driver* "() => document.querySelector('.mount-wizard__form select[name=kind]').value"))
      "a newly picked face uses its geometry hint instead of the repeated socket kind")
  (is (= "1" (s/js *driver* "() => document.querySelector('.mount-wizard__form input[name=capacity]').value")))
  (s/select-option! *driver* ".mount-wizard__form select[name=kind]" "socket")
  (is (s/wait-until
       #(= "turret" (s/js *driver* "() => document.querySelector('.mount-wizard__form select[name=accepts]').value"))))
  (s/click! *driver* ".mount-wizard__actions button[value=create]")
  (is (s/wait-until #(str/includes? (s/text *driver* "#detail") "port-2"))
      "the repeated classification still waits for an explicit save"))

;; --- the island -------------------------------------------------------------

(deftest htmx-swaps-leave-the-webgl-context-alive
  (open-app!)
  (select-part! "Cruiser Hull")
  (s/await-part *driver* s/hull-id)
  ;; Mark the live canvas from JS. If htmx ever replaces the element, the
  ;; marker goes with it - which is exactly the failure hx-preserve prevents.
  (s/js *driver* "() => { document.getElementById('viewport').__alive = 42; }")
  (testing "swap the library panel and the detail panel"
    (s/select-option! *driver* "select[name=bundle]" "Ork Fleet Bundle")
    (is (s/wait-until #(= 1 (s/count-els *driver* "#library-results .part"))))
    (select-part! "Ram Ship")
    (s/await-part *driver* s/ork-id))
  (testing "the canvas element survived both"
    (is (= 42 (s/js *driver* "() => document.getElementById('viewport').__alive"))))
  (testing "and the surviving context is still drawing"
    ;; This block used to assert the hull was **still in the scene** alongside
    ;; the ork, and that vertices had grown - using accumulation as its proof
    ;; that nothing had been rebuilt. #47 made a new selection replace the
    ;; scene, so that proof is gone, and it was never the load-bearing one: the
    ;; marker above is. A rebuilt canvas loses it.
    (let [after (s/stats *driver*)]
      (is (= [s/ork-id] (vec (:parts after)))
          "the swap did not disturb the selection policy")
      (is (pos? (:vertices after))))
    ;; Polled for the same reason as in `loading-a-part-renders-and-frames-it`:
    ;; `:draws` reports the last frame, and the assertions above can run before
    ;; one has happened.
    (is (s/wait-until #(pos? (:draws (s/stats *driver*))))
        "the same context is still rendering")))

;; --- pixels -----------------------------------------------------------------

(deftest the-canvas-is-not-blank
  (testing "the crudest question the stats hook cannot answer: did anything
            actually reach the screen"
    (open-app!)
    (select-part! "Cruiser Hull")
    (s/await-part *driver* s/hull-id)
    ;; One frame's grace: stats can report an uploaded mesh before it is drawn.
    (Thread/sleep 500)
    (let [shot (io/file (s/temp-dir "shipyard-e2e-shot") "canvas.png")]
      (s/screenshot-el! *driver* "#viewport" shot)
      (let [img    (ImageIO/read shot)
            w      (.getWidth img)
            h      (.getHeight img)
            pixels (for [x (range 0 w (max 1 (quot w 40)))
                         y (range 0 h (max 1 (quot h 40)))]
                     (.getRGB img x y))]
        (is (pos? w))
        (is (> (count (distinct pixels)) 1)
            "every sampled pixel is identical - nothing rendered")))))

;; --- degraded mode ----------------------------------------------------------

(deftest browsing-works-without-the-viewport-bundle
  (testing "with the bundle blocked, the server-rendered UI carries on (§8)"
    (let [[system server] (s/start-degraded!)
          driver (s/make-driver)]
      (try
        (s/go! driver (str "http://127.0.0.1:" (s/server-port server)))
        (s/wait-visible! driver "#library-results .part")
        (is (= "undefined" (s/js driver "() => typeof window.__shipyard"))
            "the island really is absent")
        (is (= 5 (s/count-els driver "#library-results .part")))
        (testing "filtering still works, because htmx is a separate file"
          (s/select-option! driver "select[name=bundle]" "Ork Fleet Bundle")
          (is (s/wait-until #(= 1 (s/count-els driver "#library-results .part")))))
        (testing "and a part still preprocesses and reports itself loaded"
          (s/click! driver "//button[.//span[text()='Ram Ship']]")
          (is (s/wait-until
               #(str/includes? (s/text driver "#detail") "Loaded"))))
        (finally
          (s/quit! driver)
          (.stop ^org.eclipse.jetty.server.Server server)
          (ig/halt! system))))))
