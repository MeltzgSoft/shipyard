(ns shipyard.unit.scan-test
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
  (testing "pitted beats plain; supported is never a source"
    (is (= :unsupported-pitted
           (scan/source-variant #{:supported :unsupported :unsupported-pitted})))
    (is (= :unsupported (scan/source-variant #{:supported :unsupported})))
    (is (nil? (scan/source-variant #{:supported}))
        "supported-only parts have no renderable source, but are still catalogued")))
