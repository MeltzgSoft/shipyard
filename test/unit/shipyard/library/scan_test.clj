(ns shipyard.library.scan-test
  "Role inference is pure string work, so it is unit-testable without a library.
  Names below are real folder names taken from the collection."
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.library.scan :as scan]))

(defn- hint [name & {:keys [class weapons? turrets?]}]
  (scan/role-hint {:name name :class class
                   :weapons? (boolean weapons?) :turrets? (boolean turrets?)}))

(defn- role [name & opts] (first (apply hint name opts)))
(defn- source [name & opts] (second (apply hint name opts)))

(deftest directories-beat-names
  (testing "a weapons/ segment or an ordinance class is a fact, not a guess"
    (is (= :weapon (role "Anarchist LC Lance Hull" :weapons? true)))
    (is (= :class (source "Anarchist LC Lance Hull" :weapons? true)))
    (is (= :ordinance (role "Human Assault Boat Tall" :class "ordinance")))
    (is (= :terrain (role "Orksteroid 1" :class "Terrain")))
    (is (= :class (source "Orksteroid 1" :class "Terrain")))))

(deftest turrets-are-their-own-role
  (testing "a turret drops into a socket on a weapon battery rather than mounting
            on the hull - lance batteries carry the hole, the turret fills it"
    (is (= :turret (role "Lance Turret" :weapons? true :turrets? true)))
    (is (= :class (source "Lance Turret" :weapons? true :turrets? true))
        "a turrets/ directory is a fact, not a guess"))
  (testing "the name still catches turrets in a library not yet reorganised"
    (is (= :turret (role "Lance Turret" :weapons? true)))
    (is (= :inferred (source "Lance Turret" :weapons? true))))
  (testing "a plain battery stays a weapon - it is the thing with the hole"
    (is (= :weapon (role "Lance Battery" :weapons? true)))
    (is (= :weapon (role "Weapon Battery" :weapons? true)))))

(deftest turret-housings-are-batteries-not-turrets
  (testing "a Turret Bay is the thing with the holes, not the thing that fills
            them - word order separates them, and size does not: these overlap
            real turrets exactly at 272 KB to 1.6 MB"
    (is (= :weapon (role "GGFMF Gyro Cruiser Turret Bay 1" :weapons? true)))
    (is (= :weapon (role "GGFMF Reseller Cruiser Turret Bay A" :weapons? true)))
    (is (= :weapon (role "Weapon Battery Turrets" :weapons? true)))
    (is (= :weapon (role "Weapon Battery Turrets Frontward" :weapons? true))))
  (testing "but a turret whose name merely contains 'bay' is still a turret"
    (is (= :turret (role "CB Lancebay Turret x8" :weapons? true)))
    (is (= :turret (role "Assault Combatbarge Lancebay Turret x5" :weapons? true)))))

(deftest accepts-turrets-hint
  (let [acc? #(scan/accepts-turrets? {:name % :turrets? false})]
    (testing "hulls of cruiser class and larger carry dorsal turret pits"
      (is (scan/accepts-turrets? {:name "Hull" :class "Cruiser" :role :hull}))
      (is (scan/accepts-turrets? {:name "Battleship Hull" :class "Battleship" :role :hull}))
      (is (scan/accepts-turrets? {:name "Hull" :class "Light Cruiser" :role :hull}))
      (is (scan/accepts-turrets? {:name "Hull" :class "Grand Cruiser" :role :hull}))
      (is (scan/accepts-turrets? {:name "Mid Hull" :class nil :role :hull-section})
          "single-ship bundles have no class segment and are all capital ships"))
    (testing "escorts are the one class small enough not to"
      (is (not (scan/accepts-turrets? {:name "Hull" :class "Escort" :role :hull}))))
    (testing "a non-hull part in a cruiser folder is not flagged by class alone"
      (is (not (scan/accepts-turrets? {:name "Classic Ram Prow" :class "Cruiser" :role :prow}))))
    (testing "the parts that carry turret sockets"
      (is (acc? "GGFMF Gyro Cruiser Turret Bay 1"))
      (is (acc? "Weapon Battery Turrets"))
      (is (acc? "Lance Battery") "the user's example: lance batteries take turrets")
      (is (acc? "Anarchist Battleship Spine Weapon Batteries")))
    (testing "a turret never accepts a turret"
      (is (not (scan/accepts-turrets? {:name "Lance Turret" :turrets? true})))
      (is (not (acc? "Dorsal turret"))))
    (testing "unrelated parts are untouched"
      (is (not (acc? "Hull")))
      (is (not (acc? "Classic Ram Prow"))))))

(deftest a-prow-fitted-with-a-weapon-is-still-a-prow
  (testing "26 folders under weapons/ name a prow, and every one is a prow -
            the directory must not override a more specific name"
    (is (= :prow (role "Assault Pattern Stalker Prow 1 with Lance Turret" :weapons? true)))
    (is (= :prow (role "GGDF Diplomat Torpedo Prow A" :weapons? true)))
    (is (= :prow (role "Assault Pattern Gladiator Prow 2 with Lance Turrets" :weapons? true)))
    (is (= :inferred (source "GGDF Diplomat Torpedo Prow A" :weapons? true)))))

(deftest name-rules
  (testing "core vocabulary"
    (is (= :hull (role "Hull")))
    (is (= :prow (role "Cyanide Ram Prow")))
    (is (= :bridge (role "Angel Bridge")))
    (is (= :antenna (role "Antenna 1")))
    (is (= :engine (role "Bloody Iron Engine cowl V2 mirror")))
    (is (= :weapon (role "Krooza Gunz Battery")))
    (is (= :turret (role "Lance Turret")) "turret is now its own role")
    (is (= :weapon (role "Keel Launch Bay")))
    (is (= :fin (role "Persistance Wing Fin Left")))
    (is (= :detail (role "Acid Insert")))
    (is (= :unknown (role "Hyena")))))

(deftest escort-names-are-not-role-facts
  (testing "escort parts wait for geometry classification rather than name fallback"
    (is (= :unknown (role "Cyanide Prow Python" :class "Escort")))
    (is (= :unknown (role "Mercury hull and prow" :class "Escort")))
    (is (= :inferred (source "Cyanide Prow Python" :class "Escort")))))

(deftest substring-matching-not-word-bounded
  (testing "14 folders are CamelCase or underscore-joined; word boundaries drop them all"
    (is (= :hull (role "Metis_Hull")))
    (is (= :bridge (role "GGRBridge")))
    (is (= :prow (role "GGRProw")))
    (is (= :engine (role "GGREngines")))
    (is (= :weapon (role "VossTorpedo")))
    (is (= :weapon (role "BombCanon")))
    (is (= :turret (role "Zilka_turret")) "CamelCase still matches, now as :turret")
    (is (= :weapon (role "Callisto_Battery")))))

(deftest ram-and-aft-must-be-word-bounded
  (testing "'ram' as a substring hits 9 Pyramid folders"
    (is (not= :weapon (role "Cloak Pyramid 1")))
    (is (= :weapon (role "Ram Blades")))
    ;; Under weapons/ this is still a prow: a prow fitted with a weapon is a
    ;; prow, and the name is more specific than the directory.
    (is (= :prow (role "Classic Ram Prow" :weapons? true)))
    (is (= :weapon (role "Ram Blades" :weapons? true))
        "a bare ram weapon under weapons/ stays a weapon"))
  (testing "'aft' as a substring hits 19 Crafty folders and matches nothing real"
    (is (not= :stern (role "Pirate Elves Borealis Light Cruiser Crafty Prow")))
    (is (= :prow (role "Pirate Elves Borealis Light Cruiser Crafty Prow")))))

(deftest negation-is-not-invisible
  (testing "'Ram Blades No fin' is a weapon, not a fin - and its twin 'Ram Blades'
            must agree, which the unguarded table got wrong in both directions"
    (is (= :weapon (role "Ram Blades No fin")))
    (is (= :weapon (role "Ram Blades")))
    (is (not= :fin (role "Ork Battleship No wing insert")))))

(deftest hull-sections-are-not-interchangeable-hulls
  (testing "mandatory pieces of one hull, not options - printing all three of
            Revengeful Specter's yields two ships' worth"
    (is (= :hull-section (role "Bloody Iron Forward hull")))
    (is (= :hull-section (role "Unbreakable Speculation - Mid Hull")))
    (is (= :hull-section (role "Combatbarge Front Hull")))
    (is (= :hull-section (role "GGFMF SeekerBB HullRear1")))
    (is (= :hull-section (role "Tomb Class Top Hull 1"))))
  (testing "a plain hull is still a hull"
    (is (= :hull (role "Hull")))
    (is (= :hull (role "Battleship Hull")))))

(deftest never-throws-on-anything
  (doseq [n ["" "..." "Ünïcødé Prow" "a" (apply str (repeat 300 "x"))
             "!!!" "hull/prow\\weird"]]
    (is (keyword? (role n)) (str "threw or returned non-keyword for " (pr-str n)))))

(deftest source-variant-preference
  (testing "plain unsupported beats pitted; supported and pitted-only are never sources"
    (is (= :unsupported
           (scan/source-variant #{:supported :unsupported :unsupported-pitted})))
    (is (= :unsupported (scan/source-variant #{:supported :unsupported})))
    (is (nil? (scan/source-variant #{:unsupported-pitted})))
    (is (nil? (scan/source-variant #{:supported}))
        "unsupported sources are required for a displayed part")))
