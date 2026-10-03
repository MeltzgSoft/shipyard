(ns shipyard.e2e.paint-editor-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.named-ship-test :as named]
            [shipyard.e2e.detail-brush-test :as brush]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as classes]
            [shipyard.ship.db :as ships]
            [shipyard.scheme.db :as schemes]
            [shipyard.persistence-fixture :as persisted])
  (:import [com.microsoft.playwright Page Dialog]
           [java.util.function Consumer]))

(defn- confirm! [driver accept?]
  (.onceDialog ^Page (:page driver)
               (reify Consumer (accept [_ dialog]
                                 (if accept? (.accept ^Dialog dialog) (.dismiss ^Dialog dialog))))))

(deftest customize-and-browser-tables-edit-and-delete-named-ships
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        class-db (:shipyard.loadout/db sys) ship-db (:shipyard.ship/db sys)
        scheme-db (:shipyard.scheme/db sys)
        class {:loadout/id (random-uuid) :loadout/name "Cruiser" :loadout/hull (:hull lf/draft) :loadout/slots lf/assignments}
        other (assoc class :loadout/id (random-uuid) :loadout/name "Scout" :loadout/slots {})
        palette {:scheme/id (random-uuid) :scheme/name "Fleet" :scheme/layers {}}
        scout {:ship/id (random-uuid) :ship/name "Scout vessel" :ship/class (:loadout/id other) :ship/paint {}}
        edit #(str "button[aria-label='Edit ship " % "']")
        delete #(str "button[aria-label='Delete ship " % "']")]
    (try
      (classes/put! class-db class :create)
      (classes/put! class-db other :create)
      (schemes/put! scheme-db palette :create)
      (ships/put! ship-db scout :create)
      (s/go! driver (s/base-url sys))
      (s/open-class! driver "Cruiser")
      (named/tab! driver "Customize")
      (s/select-option! driver "#paint-create select[name=scheme]" "Fleet")
      (named/create! driver "Resolute")
      (is (zero? (s/count-els driver "#paint-target, #paint-material, .paint-tools, .paint-group-controls, .paint-write, [name=cross-instances]")))
      (is (= 2 (s/count-els driver ".customize-ships tbody tr")))
      (is (= "Customize" (s/text driver ".ship-inspector nav button[aria-current=page]")))
      (let [id (get-in @(:state (:shipyard.paint/db sys)) [:draft :ship-id])
            before-class (classes/snapshot! class-db)]
        (s/input! driver "#paint-brush input[name=radius]" "2" "input")
        (apply brush/stroke! driver (brush/face-point driver [] 0))
        (brush/await-saved! driver)
        (let [details (get-in (ships/snapshot! ship-db) [:ships id :ship/paint :paint/details])]
          (s/click! driver (edit "Scout vessel"))
          (s/wait-visible! driver "#paint-brush")
          (is (s/wait-until #(= (:ship/id scout) (get-in @(:state (:shipyard.paint/db sys)) [:draft :ship-id]))))
          (is (s/wait-until #(= 1 (count (get-in (s/stats driver) [:assembly :slots])))))
          (s/click! driver (edit "Resolute"))
          (s/wait-visible! driver "#paint-brush")
          (is (s/wait-until #(seq (:details (s/slot driver [])))))
          (confirm! driver false)
          (s/click! driver (delete "Scout vessel"))
          (is (= 2 (count (:ships (ships/snapshot! ship-db)))))
          (confirm! driver true)
          (s/click! driver (delete "Scout vessel"))
          (is (s/wait-until #(= #{id} (set (keys (:ships (ships/snapshot! ship-db)))))))
          (is (s/wait-until #(= 1 (s/count-els driver ".customize-ships tbody tr"))))
          (is (= id (get-in @(:state (:shipyard.paint/db sys)) [:draft :ship-id])))
          (is (= details (get-in (persisted/records! ship-db :ships) [:ships id :ship/paint :paint/details])))
          (s/screenshot-el! driver ".stage__detail" (java.io.File. "/tmp/shipyard-customize.png")))
        (s/ship-table! driver)
        (s/click! driver ".ship-card:has([aria-label='Open class Cruiser']) summary")
        (s/wait-visible! driver (edit "Resolute"))
        (s/click! driver (edit "Resolute"))
        (s/wait-visible! driver "#paint-brush")
        (is (= id (get-in @(:state (:shipyard.paint/db sys)) [:draft :ship-id])))
        (s/ship-table! driver)
        (s/wait-visible! driver (delete "Resolute"))
        (s/screenshot-el! driver "#library" (java.io.File. "/tmp/shipyard-named-ship-actions.png"))
        (confirm! driver false)
        (s/click! driver (delete "Resolute"))
        (is (= #{id} (set (keys (:ships (ships/snapshot! ship-db))))))
        (confirm! driver true)
        (s/click! driver (delete "Resolute"))
        (is (s/wait-until #(empty? (:ships (ships/snapshot! ship-db)))))
        (is (s/wait-until #(zero? (s/count-els driver ".ship-table__named"))))
        (is (= before-class (classes/snapshot! class-db)))
        (is (= palette (get-in (schemes/snapshot! scheme-db) [:schemes (:scheme/id palette)])))
        (is (= (ships/snapshot! ship-db) (persisted/records! ship-db :ships))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest customize-table-pages-without-changing-the-current-preview
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        class-db (:shipyard.loadout/db sys) ship-db (:shipyard.ship/db sys)
        class {:loadout/id (random-uuid) :loadout/name "Cruiser" :loadout/hull (:hull lf/draft) :loadout/slots {}}]
    (try
      (classes/put! class-db class :create)
      (doseq [n (range 51)]
        (ships/put! ship-db {:ship/id (random-uuid) :ship/name (format "Vessel %02d" n)
                             :ship/class (:loadout/id class) :ship/paint {}} :create))
      (s/go! driver (s/base-url sys))
      (s/open-class! driver "Cruiser")
      (named/tab! driver "Customize")
      (is (= 50 (s/count-els driver "#customize-ships tbody tr")))
      (let [before (:draft @(:state (:shipyard.paint/db sys)))]
        (s/click! driver "#customize-ships button:text-is('Next')")
        (is (s/wait-until #(= 1 (s/count-els driver "#customize-ships tbody tr"))))
        (is (re-find #"Vessel 50" (s/text driver "#customize-ships")))
        (is (= before (:draft @(:state (:shipyard.paint/db sys)))) "Paging does not select another ship")
        (named/tab! driver "Schemes")
        (named/tab! driver "Customize")
        (is (= 1 (s/count-els driver "#customize-ships tbody tr")))
        (s/click! driver "#customize-ships button:text-is('Previous')")
        (is (s/wait-until #(= 50 (s/count-els driver "#customize-ships tbody tr")))))
      (finally (s/quit! driver) (fixture/stop! started)))))
