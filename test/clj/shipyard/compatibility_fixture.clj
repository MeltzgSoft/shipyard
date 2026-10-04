(ns shipyard.compatibility-fixture
  "Authored pieces across factions and classes for compatibility workflows."
  (:require [babashka.fs :as fs]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]))

(def foreign "Other Navy/Cruiser/Foreign Weapon")
(def foreign-universal "Other Navy/Universal/Foreign Universal")
(def escort "Synthetic Navy/Escort/Escort Weapon")

(defn library! [root]
  (fixture/library! root)
  (doseq [id [foreign foreign-universal escort]]
    (fs/create-dirs (fs/parent (fs/path root id)))
    (fs/copy-tree (fs/path root (:weapon fixture/ids)) (fs/path root id)))
  root)

(defn author! [cat]
  (fixture/author! cat)
  (doseq [[id bundle class name] [[foreign "Other Navy" "Cruiser" "Foreign Weapon"]
                                  [foreign-universal "Other Navy" "Universal" "Foreign Universal"]
                                  [escort "Synthetic Navy" "Escort" "Escort Weapon"]
                                  [(:weapon-alt fixture/ids) "Synthetic Navy" "Universal" "Universal Weapon"]]]
    (catalog/save-authoring! cat id {:part-role :weapon :mounts fixture/weapon-mounts})
    (catalog/save-metadata! cat (mapv (fn [[attribute value]] {:id id :attribute attribute :value value})
                                      [[:part/name-override name] [:part/bundle-override bundle] [:part/class-override class]]))))
