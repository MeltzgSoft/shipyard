(ns shipyard.e2e.paint-editor-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.e2e.paint-material-test :as materials]
            [shipyard.loadout-fixture :as lf]
            [shipyard.scheme.db :as schemes])
  (:import [com.microsoft.playwright Page Route APIResponse Route$FulfillOptions]
           [java.util.function Consumer]))

(defn input! [driver selector value event]
  (s/js driver (str "() => {let e=document.querySelector('" selector "');e.value='" value
                    "';e.dispatchEvent(new Event('" event "',{bubbles:true}));}")))

(deftest paint-input-preview-commit-and-instance-independence
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        state (:state (:shipyard.assembly/db sys)) store (:shipyard.scheme/db sys)]
    (try
      (swap! state assoc :draft lf/draft :root (str (:root started)))
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "assembly")
      (workspace/await-ship! driver)
      (s/click! driver "button:text-is('Paint assembly')")
      (s/click! driver ".paint-scheme-actions summary:text-is('New')")
      (s/wait-visible! driver "#paint-create")
      (is (= "Paint" (s/text driver ".masthead__mode--active")))
      (s/fill-and-blur! driver "#paint-create input" "Distinct weapons")
      (s/click! driver "#paint-create button")
      (s/wait-visible! driver "#paint-material")
      (s/click! driver "#paint-target button[data-paint-target='[[:weapon 0]]']")
      (is (s/wait-until #(= "[[:weapon 0]]" (s/js driver "() => document.querySelector('#paint-material')?.elements.target.value"))))
      (workspace/await-ship! driver)
      (let [before (slurp (str (:file store))) id (get-in @(:state (:shipyard.paint/db sys)) [:draft :scheme])]
        (input! driver "#paint-material input[name=base]" "#ff0000" "input")
        (is (s/wait-until #(= "ff0000" (:color (materials/slot driver [["weapon" 0]])))))
        (is (= "9aa4af" (:color (materials/slot driver [["weapon" 1]]))))
        (is (= before (slurp (str (:file store)))))
        (input! driver "#paint-material input[name=base]" "#ff0000" "change")
        (is (s/wait-until #(= "Material saved." (s/text driver "#paint-status"))))
        (is (= [1.0 0.0 0.0] (get-in (schemes/snapshot! (schemes/open! (:file store)))
                                     [:schemes id :scheme/instances [[:weapon 0]] :material :base])))
        (is (= lf/draft (dissoc (:draft @state) :name)))
        (let [held (atom nil) ^Page page (:page driver)]
          (.route page "**/paint/material"
                  (reify Consumer
                    (accept [_ value]
                      (let [^Route route value]
                        (if (nil? @held) (reset! held [route (.fetch route)]) (.resume route))))))
          (input! driver "#paint-material input[name=base]" "#00ff00" "input")
          (input! driver "#paint-material input[name=base]" "#00ff00" "change")
          (is (s/wait-until #(do (s/stats driver) (some? @held))))
          (is (true? (s/js driver "() => document.querySelector('[data-workspace-mode=assembly]').disabled")))
          (input! driver "#paint-material input[name=base]" "#ff0000" "input")
          (let [[^Route route ^APIResponse response] @held]
            (.fulfill route (doto (Route$FulfillOptions.) (.setResponse response))))
          (is (s/wait-until #(= "Newer preview not saved" (s/text driver "#paint-status"))))
          (is (= "ff0000" (:color (materials/slot driver [["weapon" 0]]))))
          (s/click! driver "#paint-material button.paint-primary")
          (is (s/wait-until #(= "Material saved." (s/text driver "#paint-status")))))
        (let [before (schemes/snapshot! store) file (:file store)
              backup (fs/path (:temp started) "saved-schemes.edn")]
          (fs/move file backup)
          (fs/create-dirs file)
          (input! driver "#paint-material input[name=metalness]" "0.33" "input")
          (input! driver "#paint-material input[name=metalness]" "0.33" "change")
          (s/wait-visible! driver "#paint-status [role=alert]")
          (is (= before (schemes/snapshot! store)))
          (fs/delete-if-exists file)
          (fs/move backup file)
          (s/click! driver "#paint-material button.paint-primary")
          (is (s/wait-until #(= "Material saved." (s/text driver "#paint-status")))))
        (is (= 0.33 (get-in (schemes/snapshot! (schemes/open! (:file store)))
                            [:schemes id :scheme/instances [[:weapon 0]] :material :metalness])))
        (workspace/switch! driver "assembly")
        (workspace/await-ship! driver)
        (workspace/switch! driver "paint")
        (s/wait-visible! driver "#paint-material")
        (is (s/wait-until #(= "ff0000" (:color (materials/slot driver [["weapon" 0]])))))
        (s/click! driver "button:text-is('Use inherited material')")
        (s/wait-visible! driver "#paint-material")
        (is (s/wait-until #(= "9aa4af" (:color (materials/slot driver [["weapon" 0]])))))
        (is (empty? (get-in (schemes/snapshot! store) [:schemes id :scheme/instances]))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest group-editing-precedence-and-floating-inspector
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        store (:shipyard.scheme/db sys)
        target! (fn [key]
                  (s/click! driver (str "#paint-target button[data-paint-target='" key "']"))
                  (is (s/wait-until #(= key (s/js driver "() => document.querySelector('#paint-material')?.elements.target.value")))))]
    (try
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft :root (str (:root started)))
      (s/go! driver (s/base-url sys))
      (workspace/switch! driver "assembly")
      (workspace/await-ship! driver)
      (s/click! driver "button:text-is('Paint assembly')")
      (s/click! driver ".paint-scheme-actions summary:text-is('New')")
      (s/fill-and-blur! driver "#paint-create input" "Group proof")
      (s/click! driver "#paint-create button")
      (s/wait-visible! driver "#paint-material")
      (workspace/await-ship! driver)
      (is (true? (s/js driver "() => {const c=document.querySelector('#viewport').getBoundingClientRect(),i=document.querySelector('.paint-editor').getBoundingClientRect();return c.right>=i.right&&c.left<i.left&&c.height>=i.height;}")))
      (let [id (get-in @(:state (:shipyard.paint/db sys)) [:draft :scheme])
            record #(get-in (schemes/snapshot! store) [:schemes id])
            create! (fn [name members]
                      (doseq [member members] (s/check! driver (str "#paint-target input[value='" member "']")))
                      (s/click! driver ".paint-targets summary:text-is('Group selection')")
                      (s/fill-and-blur! driver "#paint-group-create input[name=name]" name)
                      (s/click! driver "#paint-group-create button")
                      (is (s/wait-until #(= name (s/text driver ".paint-inspector-header h2")))))]
        (create! "Battery" ["[[:weapon 0]]" "[[:weapon 1]]"])
        (let [gid (:group/id (first (:scheme/groups (record))))]
          (input! driver "#paint-material input[name=base]" "#ff0000" "input")
          (is (s/wait-until #(= "ff0000" (:color (materials/slot driver [["weapon" 0]])))))
          (is (= "ff0000" (:color (materials/slot driver [["weapon" 1]]))))
          (is (nil? (:group/material (first (:scheme/groups (record))))))
          (input! driver "#paint-material input[name=base]" "#ff0000" "change")
          (is (s/wait-until #(= "Material saved." (s/text driver "#paint-status"))))
          (target! "[[:weapon 0]]")
          (input! driver "#paint-material input[name=base]" "#0000ff" "input")
          (input! driver "#paint-material input[name=base]" "#0000ff" "change")
          (is (s/wait-until #(= "Material saved." (s/text driver "#paint-status"))))
          (is (= "ff0000" (:color (materials/slot driver [["weapon" 1]]))))
          (s/click! driver "button:text-is('Use inherited material')")
          (is (s/wait-until #(= "ff0000" (:color (materials/slot driver [["weapon" 0]])))))
          (create! "Trim" ["[[:weapon 0]]"])
          (input! driver "#paint-material input[name=base]" "#00ff00" "input")
          (input! driver "#paint-material input[name=base]" "#00ff00" "change")
          (is (s/wait-until #(= "Material saved." (s/text driver "#paint-status"))))
          (is (= "ff0000" (:color (materials/slot driver [["weapon" 0]]))))
          (s/click! driver ".paint-editor summary:text-is('Manage group')")
          (s/click! driver "button:text-is('Move up')")
          (is (s/wait-until #(= "00ff00" (:color (materials/slot driver [["weapon" 0]])))))
          (is (= "ff0000" (:color (materials/slot driver [["weapon" 1]]))))
          (target! "[[:weapon 0]]")
          (s/click! driver ".paint-write button:text-is('Group')")
          (s/wait-visible! driver ".paint-write select")
          (is (= "Trim" (s/text driver ".paint-inspector-header h2")))
          (s/select-option! driver ".paint-write select" "Battery")
          (is (s/wait-until #(= (str "group/" gid) (s/js driver "() => document.querySelector('#paint-material')?.elements.target.value"))))
          (target! "[]")
          (target! (str "group/" gid))
          (is (= "[[:weapon 0]]" (s/js driver "() => [...document.querySelectorAll('.paint-write button')].find(e=>e.textContent==='Instance').value")))
          (target! "role/hull")
          (is (= "[]" (s/js driver "() => [...document.querySelectorAll('.paint-write button')].find(e=>e.textContent==='Instance').value")))
          (is (= "role/hull" (s/js driver "() => [...document.querySelectorAll('.paint-write button')].find(e=>e.textContent==='Role').value")))
          (target! (str "group/" gid))
          (is (= (schemes/snapshot! store) (schemes/snapshot! (schemes/open! (:file store)))))
          (s/screenshot-el! driver "body" (java.io.File. "/tmp/shipyard-paint-material.png"))))
      (finally (s/quit! driver) (fixture/stop! started)))))
