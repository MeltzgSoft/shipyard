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
        (ops/transfer! deps id :edit)
        (s/go! driver (s/base-url (:system started)))
        (workspace/switch! driver "ships")
        (s/click! driver (card id))
        (workspace/await-ship! driver)
        (s/select-option! driver "#ship-filters select[name=bundle]" "Synthetic Navy")
        (s/click! driver "[data-mount-colors-toggle]")
        (is (s/wait-until #(true? (:mount-colors-enabled (s/stats driver)))))
        (let [draft (:draft @state) slots (get-in (s/stats driver) [:assembly :slots]) bytes (store/snapshot! (:loadouts deps))]
          (action! driver id "Delete")
          (is (str/includes? (last @messages) "Cruiser"))
          (is (= bytes (store/snapshot! (:loadouts deps))))
          (is (= draft (:draft @state)))
          (is (= slots (get-in (s/stats driver) [:assembly :slots])))
          (is (= 2 (s/count-els driver ".ship-card")))
          (reset! decision :delete)
          (testing "deleting an unselected card preserves the displayed ship and filters"
            (action! driver (:loadout/id other) "Delete")
            (is (s/wait-until #(= 1 (s/count-els driver ".ship-card"))))
            (is (= "Cruiser" (s/text driver ".ship-inspector h2")))
            (is (= slots (get-in (s/stats driver) [:assembly :slots])))
            (is (= draft (:draft @state)))
            (is (true? (:mount-colors-enabled (s/stats driver))))
            (is (= "Synthetic Navy" (s/js driver "() => document.querySelector('#ship-filters select[name=bundle]').value"))))
          (testing "deleting the displayed ship clears its preview and detaches its editing identity"
            (action! driver id "Delete")
            (s/wait-visible! driver ".ship-inspector p:text-is('Select a saved ship to preview it.')")
            (is (s/wait-until #(empty? (get-in (s/stats driver) [:assembly :slots]))))
            (is (zero? (:visible-mount-markers (s/stats driver))))
            (is (zero? (s/count-els driver "[data-ship-slot]")))
            (is (zero? (s/count-els driver ".ship-card")))
            (is (str/includes? (s/text driver "#ship-results") "No saved ships match."))
            (is (= (-> draft (dissoc :loadout-id) (update :revision inc)) (:draft @state)))
            (is (empty? (:loadouts (persisted/records! (:loadouts deps) :loadouts))))
            (is (= library-before (library-digests (:root started)))))
          (testing "the retained draft can be saved under a new identity"
            (workspace/switch! driver "assembly")
            (workspace/await-ship! driver)
            (is (= "Cruiser" (s/js driver "() => document.querySelector('.assembly__save input[name=name]').value")))
            (s/click! driver ".assembly__save button:text-is('Save ship')")
            (is (s/wait-until #(= 1 (count (:loadouts (store/snapshot! (:loadouts deps)))))))
            (is (not= id (get-in @state [:draft :loadout-id])))
            (is (nil? (get-in (persisted/records! (:loadouts deps) :loadouts) [:loadouts id]))))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest browser-transfers-confirm-before-discarding-unsaved-work
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
        ;; A changed name travels through the backend workspace transition.
        (s/fill-and-blur! driver ".assembly__save input[name=name]" "Unsaved rename")
        (workspace/switch! driver "ships")
        (s/click! driver (card id))
        (workspace/await-ship! driver)
        (doseq [label ["Duplicate" "Edit"]]
          (testing label
            (let [before (:draft @state) slots (get-in (s/stats driver) [:assembly :slots])]
              (action! driver id label)
              (s/wait-visible! driver ".assembly-discard")
              (is (= "Ship Browser" (s/text driver ".masthead__mode--active")))
              (is (= before (:draft @state)))
              (is (= slots (get-in (s/stats driver) [:assembly :slots])))
              (s/click! driver ".assembly-discard button:text-is('Cancel')")
              (s/wait-visible! driver ".ship-inspector")
              (is (= before (:draft @state)))
              (is (= slots (get-in (s/stats driver) [:assembly :slots])))
              (action! driver id label)
              (s/click! driver (str ".assembly-discard button:text-is('Discard and " (str/lower-case label) "')"))
              (s/wait-visible! driver ".assembly__save")
              (is (= "Assemble" (s/text driver ".masthead__mode--active")))
              (workspace/await-ship! driver)
              (is (= lf/assignments (get-in @state [:draft :assignments])))
              (is (= (if (= label "Edit") id nil) (get-in @state [:draft :loadout-id])))
              (is (= (if (= label "Edit") "Cruiser" "Cruiser - Copy") (get-in @state [:draft :name])))
              (is (= bytes (store/snapshot! (:loadouts deps))))
              (workspace/switch! driver "ships")
              (workspace/await-ship! driver))))
        (testing "an unchanged saved assembly transfers immediately"
          (action! driver id "Edit")
          (s/wait-visible! driver ".assembly__save")
          (is (zero? (s/count-els driver ".assembly-discard")))
          (is (= "Assemble" (s/text driver ".masthead__mode--active")))))
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
        (let [draft (assoc (:draft @state) :name "Unsaved rename") slots (get-in (s/stats driver) [:assembly :slots])]
          (s/click! driver ".assembly__hull button")
          (s/wait-visible! driver ".assembly-discard")
          (is (= draft (:draft @state)))
          (is (= slots (get-in (s/stats driver) [:assembly :slots])))
          (s/click! driver ".assembly-discard button:text-is('Cancel')")
          (is (s/wait-until #(zero? (s/count-els driver ".assembly-discard"))))
          (is (= draft (:draft @state)))
          (is (= "Unsaved rename" (s/js driver "() => document.querySelector('.assembly__save input[name=name]').value")))
          (is (= slots (get-in (s/stats driver) [:assembly :slots])))
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
