(ns shipyard.e2e.workspace-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.http.jobs :as jobs]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as store]
            [shipyard.loadout.operations :as operations])
  (:import [com.microsoft.playwright APIResponse Page Route Route$FulfillOptions]
           [java.util.concurrent CountDownLatch ExecutorService TimeUnit]
           [java.util.function Consumer]))

(defn switch! [driver mode]
  (s/click! driver (str ".masthead [data-workspace-mode='" mode "']"))
  (when-not (s/wait-until #(= mode (s/js driver "() => document.querySelector('.masthead__mode--active').dataset.workspaceMode")))
    (throw (ex-info "Workspace navigation did not complete" {:mode mode}))))

(defn await-ship! [driver]
  (is (s/wait-until #(= 13 (count (get-in (s/stats driver) [:assembly :slots]))))))

(deftest saved-ship-preview-edit-duplicate-and-workspace-isolation
  (s/assert-bundle!)
  (let [started (fixture/start! true) driver (s/make-driver) deps (lf/deps started)
        state (get-in deps [:assembly :state])]
    (try
      (swap! state assoc :draft (assoc lf/draft :scheme (random-uuid)))
      (let [source (:loadout (operations/save! deps 1 "Original"))
            id (:loadout/id source) file (:file (:loadouts deps))
            card (str ".ship-card[data-loadout-id='" id "']")]
        (operations/transfer! deps id :edit)
        (swap! state assoc :draft (assoc lf/draft :assignments (assoc lf/assignments [[:prow 0]] (:prow-alt fixture/ids))))
        (s/go! driver (s/base-url (:system started)))
        (s/wait-visible! driver "#library-results .part")
        (is (= ["Orient" "Part Browser" "Assemble" "Ship Browser"]
               (s/js driver "() => [...document.querySelectorAll('.masthead__mode')].map(e=>e.textContent)")))
        (s/click! driver ".part__select:has(.part__name:text-is('bridge'))")
        (s/await-part driver (:bridge fixture/ids))
        (switch! driver "assembly")
        (await-ship! driver)
        (s/click! driver "[data-mount-colors-toggle]")
        (is (s/wait-until #(false? (:mount-colors-enabled (s/stats driver)))))
        (switch! driver "ships")
        (s/wait-visible! driver card)
        (is (empty? (get-in (s/stats driver) [:assembly :slots])))
        (let [draft-before (:draft @state)]
          (s/wait-visible! driver "[data-mount-colors-toggle]")
          (is (= "false" (s/js driver "() => document.querySelector('[data-mount-colors-toggle]').getAttribute('aria-pressed')")))
          (is (zero? (s/count-els driver ".ship-card button:text-is('Preview')")))
          (s/click! driver card)
          (await-ship! driver)
          (is (= 13 (s/count-els driver "[data-ship-slot]")))
          (is (= draft-before (:draft @state)))
          (is (false? (:mount-colors-enabled (s/stats driver))))
          (is (every? #{"9aa4af"} (:materials (s/stats driver))))
          (is (= ["rgb(154, 164, 175)"] (s/js driver "() => [...new Set([...document.querySelectorAll('.ship-tree__color')].map(e=>getComputedStyle(e).backgroundColor))]")))
          (is (= ["[]" "[[:antenna 0]]" "[[:antenna 1]]" "[[:bridge 0]]"
                  "[[:mirrored-weapon 0]]" "[[:mirrored-weapon 0] [:turret 0]]"
                  "[[:mirrored-weapon 1]]" "[[:mirrored-weapon 1] [:turret 0]]"
                  "[[:prow 0]]" "[[:weapon 0]]" "[[:weapon 0] [:turret 0]]"
                  "[[:weapon 1]]" "[[:weapon 1] [:turret 0]]"]
                 (s/js driver "() => [...document.querySelectorAll('[data-ship-slot]')].map(e=>e.dataset.shipSlot)")))
          (s/click! driver "[data-mount-colors-toggle]")
          (is (s/wait-until #(true? (:mount-colors-enabled (s/stats driver)))))
          (is (> (count (set (:materials (s/stats driver)))) 1))
          (is (> (s/js driver "() => new Set([...document.querySelectorAll('.ship-tree__color')].map(e=>getComputedStyle(e).backgroundColor)).size") 1))
          (is (= "true" (s/js driver "() => document.querySelector('[data-mount-colors-toggle]').getAttribute('aria-pressed')")))
          (is (= "ships" (:workspace (s/stats driver)))))
        (switch! driver "browse")
        (s/await-part driver (:bridge fixture/ids))
        (switch! driver "assembly")
        (await-ship! driver)
        (is (s/wait-until #(false? (:mount-colors-enabled (s/stats driver)))))
        (is (zero? (:visible-mount-markers (s/stats driver))))
        (is (some #(= (:prow-alt fixture/ids) (:part-id %)) (get-in (s/stats driver) [:assembly :slots])))
        (switch! driver "ships")
        (await-ship! driver)
        (is (true? (:mount-colors-enabled (s/stats driver))))
        (is (> (count (set (:materials (s/stats driver)))) 1))
        (is (= "true" (s/js driver "() => document.querySelector('[data-mount-colors-toggle]').getAttribute('aria-pressed')")))
        (let [bytes (slurp (str file))]
          (s/click! driver (str card " button:text-is('Duplicate')"))
          (s/click! driver ".assembly-discard button:text-is('Discard and duplicate')")
          (s/wait-visible! driver ".assembly__save")
          (await-ship! driver)
          (is (= "Assemble" (s/text driver ".masthead__mode--active")))
          (is (= "Original - Copy" (s/js driver "() => document.querySelector('.assembly__save input[name=name]').value")))
          (is (= bytes (slurp (str file))))
          (is (nil? (get-in @state [:draft :loadout-id])))
          (is (= (:loadout/scheme source) (get-in @state [:draft :scheme])))
          (s/fill-and-blur! driver ".assembly__save input[name=name]" "Independent copy")
          (s/click! driver ".assembly__save button")
          (is (s/wait-until #(= 2 (count (:loadouts (store/snapshot! (:loadouts deps)))))))
          (let [records (:loadouts (store/snapshot! (store/open! file)))]
            (is (= source (get records id)))
            (is (not= id (get-in @state [:draft :loadout-id])))))
        (testing "Edit preserves identity and updates only the selected ship"
          (switch! driver "ships")
          (s/wait-visible! driver card)
          (s/click! driver (str card " button:text-is('Edit')"))
          (s/wait-visible! driver ".assembly__save")
          (is (= "Assemble" (s/text driver ".masthead__mode--active")))
          (is (= id (get-in @state [:draft :loadout-id])))
          (s/fill-and-blur! driver ".assembly__save input[name=name]" "Edited source")
          (s/click! driver ".assembly__save button")
          (is (s/wait-until #(= "Edited source" (get-in (store/snapshot! (:loadouts deps)) [:loadouts id :loadout/name]))))
          (is (= 2 (count (:loadouts (store/snapshot! (store/open! file))))))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest orient-dirty-session-round-trip
  (let [started (fixture/start! true) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url (:system started)))
      (switch! driver "orient")
      (s/wait-visible! driver "[data-bulk-select]")
      (s/check! driver (str "[data-bulk-select][value='" (:prow fixture/ids) "']"))
      (s/click! driver "[data-bulk-render-button]")
      (is (s/wait-until #(= 1 (get-in (s/stats driver) [:bulk :count]))))
      (s/fill-and-blur! driver "[data-bulk-angle][data-axis=y]" "45")
      (s/click! driver "[data-bulk-step='15']")
      (let [before (:bulk (s/stats driver))]
        (switch! driver "assembly")
        (s/wait-visible! driver ".assembly__hull")
        (is (empty? (:parts (s/stats driver))))
        (switch! driver "orient")
        (s/wait-visible! driver "[data-bulk-grid]")
        (is (s/wait-until #(= before (:bulk (s/stats driver)))))
        (is (= "true" (s/js driver "() => document.querySelector('[data-bulk-step=\"15\"]').getAttribute('aria-pressed')")))
        (is (= "1 selected" (s/text driver "[data-bulk-count]"))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest ship-filters-and-failed-preview-preserve-state
  (let [started (fixture/start! true
                                (fn [root]
                                  (fixture/library! root)
                                  (fs/copy-tree (fs/path root "Synthetic Navy" "Cruiser")
                                                (fs/path root "Other Navy" "Escort"))
                                  root))
        deps (lf/deps started) driver (s/make-driver)
        original {:loadout/id (random-uuid) :loadout/name "Navy cruiser"
                  :loadout/hull (:hull fixture/ids) :loadout/slots lf/assignments}
        other (-> original
                  (assoc :loadout/id (random-uuid) :loadout/name "Other escort")
                  (update :loadout/hull str/replace "Synthetic Navy/Cruiser" "Other Navy/Escort")
                  (update :loadout/slots #(update-vals % (fn [id] (str/replace id "Synthetic Navy/Cruiser" "Other Navy/Escort")))))
        missing (assoc original :loadout/id (random-uuid) :loadout/name "Missing hull" :loadout/hull "gone")]
    (try
      (doseq [record [original other missing]] (store/put! (:loadouts deps) record :create))
      (s/go! driver (s/base-url (:system started)))
      (switch! driver "ships")
      (is (s/wait-until #(= 3 (s/count-els driver ".ship-card"))))
      (s/select-option! driver "#ship-filters select[name=bundle]" "Other Navy")
      (is (s/wait-until #(= 1 (s/count-els driver ".ship-card"))))
      (is (str/includes? (s/text driver ".ship-card") "Other escort"))
      (s/select-option! driver "#ship-filters select[name=class]" "Escort")
      (switch! driver "browse")
      (s/wait-visible! driver "#filters")
      (switch! driver "ships")
      (s/wait-visible! driver "#ship-filters")
      (is (= ["Other Navy" "Escort"] (s/js driver "() => [...document.querySelectorAll('#ship-filters select')].map(e=>e.value)")))
      (is (= 1 (s/count-els driver ".ship-card")))
      (s/select-option! driver "#ship-filters select[name=bundle]" "All bundle / faction")
      (s/select-option! driver "#ship-filters select[name=class]" "All class")
      (is (s/wait-until #(= 3 (s/count-els driver ".ship-card"))))
      (.press (.locator ^Page (:page driver) ".ship-card__load[aria-label='Load Navy cruiser']") "Enter")
      (await-ship! driver)
      (let [before (get-in (s/stats driver) [:assembly :slots])]
        (s/click! driver ".ship-card:has(.ship-card__load[aria-label='Load Missing hull']) button:text-is('Edit')")
        (s/wait-visible! driver ".ship-inspector [role=alert]")
        (is (= "Ship Browser" (s/text driver ".masthead__mode--active")))
        (is (= before (get-in (s/stats driver) [:assembly :slots])))
        (is (= "Navy cruiser" (s/text driver ".ship-inspector h2"))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest late-part-response-cannot-replace-a-new-activation
  (let [started (fixture/start! true) driver (s/make-driver)
        held (atom nil) ^Page page (:page driver)]
    (try
      (.route page "**/part/**"
              (reify Consumer
                (accept [_ value]
                  (let [^Route route value]
                    (if (nil? @held)
                      (let [response (.fetch route)] (reset! held [route response]))
                      (.resume route))))))
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver "#library-results .part")
      (s/js driver "() => { window.lateCompleted=false; window.lateCaptured=false; document.body.addEventListener('htmx:beforeRequest', e => { if(!window.lateCaptured && e.detail.pathInfo.requestPath.includes('/bridge')) { window.lateCaptured=true; e.detail.xhr.addEventListener('loadend', () => window.lateCompleted=true); } }); }")
      (s/click! driver ".part__select:has(.part__name:text-is('bridge'))")
      (is (s/wait-until #(do (s/stats driver) (some? @held))))
      (switch! driver "assembly")
      (s/wait-visible! driver ".assembly__hull")
      (switch! driver "browse")
      (s/await-part driver (:bridge fixture/ids))
      (s/click! driver ".part__select:has(.part__name:text-is('prow'))")
      (s/await-part driver (:prow fixture/ids))
      (let [[^Route route ^APIResponse response] @held]
        (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))
      (is (s/wait-until #(s/js driver "() => window.lateCompleted")))
      (is (= [(:prow fixture/ids)] (:parts (s/stats driver))))
      (is (= "prow" (s/text driver ".detail__name")))
      (is (= "Part Browser" (s/text driver ".masthead__mode--active")))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest workspace-controls-work-without-the-viewport-bundle
  (let [started (fixture/start! true) driver (s/make-driver) ^Page page (:page driver)
        state (:state (:shipyard.workspace/db (:system started)))]
    (try
      (.route page "**/js/viewport.js" (reify Consumer (accept [_ route] (.abort ^Route route))))
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver "#filters")
      (is (nil? (s/js driver "() => window.__shipyard || null")))
      (switch! driver "orient")
      (s/wait-visible! driver "[data-bulk-select]")
      (s/check! driver (str "[data-bulk-select][value='" (:prow fixture/ids) "']"))
      (is (s/wait-until #(= "1 selected" (s/text driver "[data-bulk-count]"))))
      (is (= (pr-str [(:prow fixture/ids)]) (get-in @state [:workspaces :orient :selection])))
      (switch! driver "assembly")
      (s/wait-visible! driver ".assembly__hull")
      (is (= "Assemble" (s/text driver ".masthead__mode--active")))
      (s/click! driver "[data-mount-colors-toggle]")
      (is (s/wait-until #(= "false" (s/js driver "() => document.querySelector('[data-mount-colors-toggle]').getAttribute('aria-pressed')"))))
      (is (false? (get-in @state [:workspaces :assembly :colors])))
      (switch! driver "orient")
      (s/wait-visible! driver "[data-bulk-select]")
      (is (= "1 selected" (s/text driver "[data-bulk-count]")))
      (s/click! driver "[data-bulk-render-button]")
      (s/wait-visible! driver "[data-bulk-grid]")
      (s/click! driver "[data-bulk-back]")
      (s/wait-visible! driver "[data-bulk-select]")
      (is (zero? (s/count-els driver "[data-bulk-grid]")))
      (.reload page)
      (s/wait-visible! driver "[data-bulk-select]")
      (is (= "Orient" (s/text driver ".masthead__mode--active")))
      (is (= "1 selected" (s/text driver "[data-bulk-count]")))
      (switch! driver "assembly")
      (s/wait-visible! driver ".assembly__hull")
      (is (= "false" (s/js driver "() => document.querySelector('[data-mount-colors-toggle]').getAttribute('aria-pressed')")))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest back-to-table-survives-a-poll-during-the-click
  (let [started (fixture/start! true) driver (s/make-driver) ^Page page (:page driver)
        held (atom nil) entered (CountDownLatch. jobs/threads) release (CountDownLatch. 1)
        ^ExecutorService pool (get-in started [:system :shipyard.http/jobs :pool])
        state (:state (:shipyard.workspace/db (:system started)))]
    (try
      ;; Keep real preprocessing pending; navigation must still work without
      ;; the viewport bundle and while a real polling response replaces the grid.
      (dotimes [_ jobs/threads]
        (.submit pool ^Runnable (fn [] (.countDown entered) (.await release 120 TimeUnit/SECONDS))))
      (is (.await entered 10 TimeUnit/SECONDS))
      (.route page "**/js/viewport.js" (reify Consumer (accept [_ route] (.abort ^Route route))))
      (.route page "**/orient/render"
              (reify Consumer
                (accept [_ value]
                  (let [^Route route value]
                    (if (and (nil? @held) (str/includes? (.postData (.request route)) "poll=1"))
                      (reset! held [route (.fetch route)])
                      (.resume route))))))
      (s/go! driver (s/base-url (:system started)))
      (switch! driver "orient")
      (s/check! driver (str "[data-bulk-select][value='" (:prow fixture/ids) "']"))
      (s/click! driver "[data-bulk-render-button]")
      (s/wait-visible! driver "[data-bulk-grid]")
      (is (s/wait-until #(do (s/js driver "() => true") (some? @held))))
      (s/js driver "() => { window.pollSwaps=0; document.body.addEventListener('htmx:afterSwap', e => { if(e.detail.target.matches('.bulk-grid__cards')) window.pollSwaps++; }); }")
      (let [{:keys [x y width height]} (s/bounds driver "[data-bulk-back]")
            mouse (.mouse page)]
        (.move mouse (+ x (/ width 2)) (+ y (/ height 2)))
        (.down mouse)
        ;; Deliver the response between pointer down and up, deterministically
        ;; reproducing the lost Back click seen on a busy CI runner.
        (let [[^Route route ^APIResponse response] @held]
          (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))
        (is (s/wait-until #(pos? (s/js driver "() => window.pollSwaps"))))
        (.up mouse))
      (s/wait-visible! driver "[data-bulk-select]")
      (is (zero? (s/count-els driver "[data-bulk-grid]")))
      (is (= "1 selected" (s/text driver "[data-bulk-count]")))
      (is (false? (get-in @state [:workspaces :orient :grid?])))
      (finally (.countDown release) (s/quit! driver) (fixture/stop! started)))))

(deftest initial-restoration-keeps-navigation-locked-until-ready
  (let [started (fixture/start! true) driver (s/make-driver) ^Page page (:page driver)
        held (atom nil)
        state (:state (:shipyard.workspace/db (:system started)))]
    (try
      (.route page "**/workspace/*?resume=1"
              (reify Consumer
                (accept [_ value]
                  (let [^Route route value]
                    (reset! held [route (.fetch route)])))))
      ;; First open, then reload the workspace reached by the previous click.
      ;; Hold the real restore response so this cannot pass by winning a race.
      (doseq [[destination panel] [["assembly" ".assembly__hull"]
                                   ["orient" "[data-bulk-select]"]]]
        (reset! held nil)
        (s/go! driver (s/base-url (:system started)))
        (is (s/wait-until #(do (s/stats driver) (some? @held))))
        (is (true? (s/js driver "() => [...document.querySelectorAll('.masthead__mode')].every(e => e.disabled)"))
            "restoration must finish before another workspace transition can start")
        (let [[^Route route ^APIResponse response] @held]
          (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))
        (switch! driver destination)
        (s/wait-visible! driver panel)
        (is (= destination (name (:active @state))))
        (is (= destination (:workspace (s/stats driver))))
        (is (= (str (:activation @state))
               (s/js driver "() => document.getElementById('workspace-context').dataset.activation"))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest pending-navigation-keeps-server-and-visible-context-together
  (let [started (fixture/start! true) driver (s/make-driver) ^Page page (:page driver)
        held (atom nil)]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver "#filters")
      (.route page "**/workspace/assembly*"
              (reify Consumer
                (accept [_ route]
                  (let [^Route route route] (reset! held [route (.fetch route)])))))
      (s/click! driver "[data-workspace-mode=assembly]")
      (is (s/wait-until #(do (s/stats driver) (some? @held))))
      (is (= "Part Browser" (s/text driver ".masthead__mode--active")))
      (is (true? (s/js driver "() => [...document.querySelectorAll('.masthead__mode')].every(e => e.disabled)")))
      (let [[^Route route ^APIResponse response] @held]
        (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))
      (s/wait-visible! driver ".assembly__hull")
      (is (= "Assemble" (s/text driver ".masthead__mode--active")))
      (is (= "assembly" (:workspace (s/stats driver))))
      (switch! driver "orient")
      (s/wait-visible! driver "[data-bulk-select]")
      (is (= "Orient" (s/text driver ".masthead__mode--active")))
      (reset! held nil)
      (.route page "**/orient/selection"
              (reify Consumer
                (accept [_ route]
                  (let [^Route route route] (reset! held [route (.fetch route)])))))
      (s/check! driver (str "[data-bulk-select][value='" (:prow fixture/ids) "']"))
      (is (s/wait-until #(do (s/stats driver) (some? @held))))
      (is (true? (s/js driver "() => document.querySelector('[data-bulk-render-button]').disabled")))
      (let [[^Route route ^APIResponse response] @held]
        (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))
      (is (s/wait-until #(= "1 selected" (s/text driver "[data-bulk-count]"))))
      (is (false? (s/js driver "() => document.querySelector('[data-bulk-render-button]').disabled")))
      (finally (s/quit! driver) (fixture/stop! started)))))
