(ns shipyard.e2e.incomplete-ships-test
  (:require [shipyard.persistence-fixture :as persisted]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as store])
  (:import [com.microsoft.playwright Page]))

(defn- card [id] (str ".ship-card[data-loadout-id='" id "']"))
(defn- await-count! [driver n]
  (is (s/wait-until #(= n (count (get-in (s/stats driver) [:assembly :slots]))))))

(deftest incomplete-ships-preview-edit-duplicate-and-count-empty-mounts
  (let [started (fixture/start! true) deps (lf/deps started) driver (s/make-driver)
        state (get-in deps [:assembly :state]) store (:loadouts deps)
        empty-path [[:weapon 0] [:turret 0]]
        assignments (dissoc lf/assignments empty-path)
        original {:loadout/id (random-uuid) :loadout/name "Almost finished"
                  :loadout/hull (:hull fixture/ids) :loadout/slots assignments}
        id (:loadout/id original)
        hull-only (assoc original :loadout/id (random-uuid) :loadout/name "Hull only" :loadout/slots {})]
    (try
      ;; Existing sparse records must load as well as new partial saves.
      (doseq [record [original hull-only]] (store/put! store record :create))
      (s/go! driver (s/base-url (:system started)))
      (workspace/switch! driver "ships")
      (s/wait-visible! driver (card id))
      (is (= "1 empty mount" (s/text driver (str (card id) " .ship-card__empty-mounts"))))
      (is (= "8 empty mounts" (s/text driver (str (card (:loadout/id hull-only)) " .ship-card__empty-mounts"))))
      (s/click! driver (card id))
      (await-count! driver 12)
      (is (= 12 (s/count-els driver "[data-ship-slot]")))
      (is (nil? (get-in @state [:draft :hull])))
      (testing "duplicate preserves the partial configuration and writes only on Save"
        (let [bytes (store/snapshot! (:loadouts deps))]
          (s/click! driver (str (card id) " button:text-is('Duplicate')"))
          (s/wait-visible! driver ".assembly__save")
          (await-count! driver 12)
          (is (= "Almost finished - Copy" (s/js driver "() => document.querySelector('.assembly__save input[name=name]').value")))
          (is (= assignments (get-in @state [:draft :assignments])))
          (is (nil? (get-in @state [:draft :loadout-id])))
          (is (= bytes (store/snapshot! (:loadouts deps))))
          (s/click! driver ".assembly__save button")
          (is (s/wait-until #(= 3 (count (:loadouts (store/snapshot! store)))))))
        (let [records (:loadouts (persisted/records! store :loadouts)) copy-id (get-in @state [:draft :loadout-id])]
          (is (not= id copy-id))
          (is (= original (get records id)))
          (is (= assignments (:loadout/slots (get records copy-id))))))
      (workspace/switch! driver "ships")
      (s/wait-visible! driver (card id))
      (s/click! driver (str (card id) " button:text-is('Edit')"))
      (s/wait-visible! driver ".assembly__save")
      (await-count! driver 12)
      (is (= id (get-in @state [:draft :loadout-id])))
      (testing "filling the last nested mount removes the tag after saving"
        (let [slot (str ".assembly__slot[data-slot='" (pr-str empty-path) "']")]
          (s/click! driver (str slot " button:has(.assembly__candidate-name:text-is('turret'))")))
        (await-count! driver 13)
        (s/click! driver ".assembly__save button")
        (is (s/wait-until #(= lf/assignments (get-in (store/snapshot! store) [:loadouts id :loadout/slots]))))
        (workspace/switch! driver "ships")
        (s/wait-visible! driver (card id))
        (is (zero? (s/count-els driver (str (card id) " .ship-card__empty-mounts")))))
      (testing "clearing a parent counts its empty mount, not its removed descendants"
        (s/click! driver (str (card id) " button:text-is('Edit')"))
        (s/wait-visible! driver ".assembly__save")
        (await-count! driver 13)
        (s/click! driver ".assembly__slot-wrap:has(> .assembly__slot[data-slot='[[:weapon 0]]']) > .assembly__mount-actions button")
        (await-count! driver 11)
        (s/click! driver ".assembly__save button")
        (is (s/wait-until #(= 10 (count (get-in (store/snapshot! store) [:loadouts id :loadout/slots])))))
        (workspace/switch! driver "ships")
        (s/wait-visible! driver (card id))
        (is (= "1 empty mount" (s/text driver (str (card id) " .ship-card__empty-mounts")))))
      (testing "a hull-only saved ship reloads with its plural tag and one viewport part"
        (s/click! driver (card (:loadout/id hull-only)))
        (await-count! driver 1)
        (.reload ^Page (:page driver))
        (s/wait-visible! driver (card (:loadout/id hull-only)))
        (await-count! driver 1)
        (is (= 1 (s/count-els driver "[data-ship-slot]")))
        (is (= "8 empty mounts" (s/text driver (str (card (:loadout/id hull-only)) " .ship-card__empty-mounts"))))
        (is (= 3 (count (:loadouts (persisted/records! store :loadouts))))))
      (finally (s/quit! driver) (fixture/stop! started)))))
