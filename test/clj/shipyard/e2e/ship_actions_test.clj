(ns shipyard.e2e.ship-actions-test
  (:require [shipyard.persistence-fixture :as persisted]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digest :as digest]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as store]
            [shipyard.loadout.operations :as ops])
  (:import [com.microsoft.playwright Dialog Page]
           [java.util.function Consumer]))

(defn- card [id] (str ".ship-card[data-loadout-id='" id "']"))
(defn- action! [driver id label]
  (s/click! driver (str (card id) " button:text-is('" label "')")))

(defn- library-digests [root]
  (into {} (for [file (fs/glob root "**") :when (fs/regular-file? file)]
             [(str (fs/relativize root file)) (digest/sha-256 (fs/file file))])))

(deftest delete-saved-ships-without-deleting-library-or-editing-work
  (let [started (fixture/start! true) deps (lf/deps started) driver (s/make-driver)
        state (get-in deps [:assembly :state]) decision (atom :cancel) messages (atom [])]
    (try
      (.onDialog ^Page (:page driver)
                 (reify Consumer
                   (accept [_ dialog]
                     (let [^Dialog dialog dialog]
                       (swap! messages conj (.message dialog))
                       (if (= :delete @decision) (.accept dialog) (.dismiss dialog))))))
      (swap! state assoc :draft lf/draft)
      (let [saved (:loadout (ops/save! deps 1 "Cruiser")) id (:loadout/id saved)
            other (assoc saved :loadout/id (random-uuid) :loadout/name "Other")
            library-before (library-digests (:root started))]
        (store/put! (:loadouts deps) other :create)
        (s/go! driver (s/base-url (:system started)))
        (s/open-class! driver "Cruiser")
        (workspace/await-ship! driver)
        (s/ship-table! driver)
        (s/select-option! driver "#ship-filters select[name=bundle]" "Synthetic Navy")
        (let [draft (:draft @state) bytes (store/snapshot! (:loadouts deps))]
          (action! driver id "Delete")
          (is (str/includes? (last @messages) "Cruiser"))
          (is (= bytes (store/snapshot! (:loadouts deps))))
          (is (= draft (:draft @state)))
          (reset! decision :delete)
          (action! driver (:loadout/id other) "Delete")
          (is (s/wait-until #(= 1 (s/count-els driver ".ship-card"))))
          (is (= draft (:draft @state)))
          (is (= "Synthetic Navy" (s/js driver "() => document.querySelector('#ship-filters select[name=bundle]').value")))
          (action! driver id "Delete")
          (is (s/wait-until #(zero? (s/count-els driver ".ship-card"))))
          (is (= (-> draft (dissoc :loadout-id) (update :revision inc)) (:draft @state)))
          (is (empty? (:loadouts (persisted/records! (:loadouts deps) :loadouts))))
          (is (= library-before (library-digests (:root started))))
          (s/open-assembly! driver)
          (workspace/await-ship! driver)
          (s/click! driver ".assembly__save button:text-is('Save class')")
          (is (s/wait-until #(= 1 (count (:loadouts (store/snapshot! (:loadouts deps)))))))
          (is (not= id (get-in @state [:draft :loadout-id])))
          (is (nil? (get-in (persisted/records! (:loadouts deps) :loadouts) [:loadouts id])))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest browser-transfers-confirm-before-discarding-unsaved-work
  (let [started (fixture/start! true) deps (lf/deps started) driver (s/make-driver)
        state (get-in deps [:assembly :state])]
    (try
      (swap! state assoc :draft lf/draft)
      (let [saved (:loadout (ops/save! deps 1 "Cruiser")) id (:loadout/id saved)
            other (assoc saved :loadout/id (random-uuid) :loadout/name "Other")]
        (store/put! (:loadouts deps) other :create)
        (s/go! driver (s/base-url (:system started)))
        (s/open-class! driver "Cruiser")
        (workspace/await-ship! driver)
        (s/fill-and-blur! driver ".assembly__save input[name=name]" "Unsaved rename")
        (s/ship-table! driver)
        (doseq [[target label confirm] [[id "Duplicate" "Discard and duplicate"] [(:loadout/id other) "Edit" "Discard and open"]]]
          (let [before (:draft @state) records (store/snapshot! (:loadouts deps))]
            (action! driver target label)
            (s/wait-visible! driver ".assembly-discard")
            (is (= before (:draft @state)))
            (s/click! driver ".assembly-discard button:text-is('Cancel')")
            (s/wait-visible! driver "#ship-filters")
            (is (= before (:draft @state)))
            (action! driver target label)
            (s/click! driver (str ".assembly-discard button:text-is('" confirm "')"))
            (s/wait-visible! driver ".assembly__save")
            (is (= (if (= label "Edit") target nil) (get-in @state [:draft :loadout-id])))
            (is (= records (store/snapshot! (:loadouts deps))))
            (s/ship-table! driver)))
        (s/open-class! driver "Other")
        (is (zero? (s/count-els driver ".assembly-discard"))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest start-assembly-confirms-unsaved-ship-and-unsaved-name
  (let [started (fixture/start! true) deps (lf/deps started) driver (s/make-driver)
        state (get-in deps [:assembly :state])]
    (try
      (swap! state assoc :draft lf/draft)
      (let [saved (:loadout (ops/save! deps 1 "Cruiser")) id (:loadout/id saved)
            bytes (store/snapshot! (:loadouts deps))]
        (ops/transfer! deps id :edit)
        (s/go! driver (s/base-url (:system started)))
        (workspace/switch! driver "assembly")
        (workspace/await-ship! driver)
        (s/fill-and-blur! driver ".assembly__save input[name=name]" "Unsaved rename")
        (let [draft (assoc (:draft @state) :name "Unsaved rename")
              ;; The confirmation panel can resize the canvas. Compare source,
              ;; transforms and material buffers rather than screen coordinates.
              scene! #(mapv (fn [slot] (dissoc slot :face-centers :paint-preparation))
                            (get-in (s/stats driver) [:assembly :slots]))
              slots (scene!)]
          (s/click! driver ".assembly__hull button")
          (s/wait-visible! driver ".assembly-discard")
          (is (= draft (:draft @state)))
          (is (= slots (scene!)))
          (s/click! driver ".assembly-discard button:text-is('Cancel')")
          (is (s/wait-until #(zero? (s/count-els driver ".assembly-discard"))))
          (is (= draft (:draft @state)))
          (is (= "Unsaved rename" (s/js driver "() => document.querySelector('.assembly__save input[name=name]').value")))
          (is (= slots (scene!)))
          (s/click! driver ".assembly__hull button")
          (s/click! driver ".assembly-discard button:text-is('Discard and start assembly')")
          (is (s/wait-until #(= 1 (count (get-in (s/stats driver) [:assembly :slots]))))))
        (is (nil? (get-in @state [:draft :loadout-id])))
        (is (empty? (get-in @state [:draft :assignments])))
        (testing "the newly started unsaved hull also requires confirmation"
          (let [draft (:draft @state)]
            (s/click! driver ".assembly__hull button")
            (s/wait-visible! driver ".assembly-discard")
            (s/click! driver ".assembly-discard button:text-is('Cancel')")
            (is (s/wait-until #(zero? (s/count-els driver ".assembly-discard"))))
            (is (= draft (:draft @state)))))
        (is (= bytes (store/snapshot! (:loadouts deps))))
        (is (= saved (get-in (persisted/records! (:loadouts deps) :loadouts) [:loadouts id]))))
      (finally (s/quit! driver) (fixture/stop! started)))))
