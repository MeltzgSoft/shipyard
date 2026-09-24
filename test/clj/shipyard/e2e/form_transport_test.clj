(ns shipyard.e2e.form-transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]
            [shipyard.e2e.workspace-test :as workspace]
            [shipyard.e2e.orient-save-test :as orient]
            [shipyard.loadout-fixture :as lf]
            [shipyard.loadout.db :as loadouts]))

(defn- assert-post-forms! [driver expected]
  (let [forms (s/js driver "() => Array.from(document.querySelectorAll('form[hx-post]'), f => ({endpoint:f.getAttribute('hx-post'), method:f.method, action:f.getAttribute('action')}))")
        endpoints (set (map :endpoint forms))]
    (doseq [endpoint expected]
      (is (contains? endpoints endpoint) (str "Rendered mutation form: " endpoint)))
    (doseq [{:keys [endpoint method action]} forms]
      (is (= "post" method) (str endpoint " must send native submissions in the body"))
      (is (= endpoint action) (str endpoint " must use the same native and HTMX destination")))))

(deftest mutation-forms-use-post-across-workspaces
  (s/assert-bundle!)
  (let [started (fixture/start! true) sys (:system started) driver (s/make-driver)
        id (random-uuid)]
    (try
      (swap! (:state (:shipyard.assembly/db sys)) assoc :draft lf/draft :root (str (:root started)))
      (loadouts/put! (:shipyard.loadout/db sys)
                     {:loadout/id id :loadout/name "Transport proof" :loadout/hull (:hull fixture/ids) :loadout/slots {}} :create)
      (s/go! driver (s/base-url sys))
      (testing "Part metadata, mounts, settings and region forms"
        (s/click! driver ".part__select:has(.part__name:text-is('weapon'))")
        (s/await-part driver (:weapon fixture/ids))
        (assert-post-forms! driver ["/settings" "/parts/role" "/parts/orientation" "/mounts/edit" "/mounts/delete" "/parts/regions"])
        (s/click! driver "[data-detail-tab=mounts]")
        (s/click! driver ".mounts__action button:text-is('Edit') >> nth=0")
        (s/wait-visible! driver ".mount-wizard__form")
        (assert-post-forms! driver ["/mounts"]))
      (testing "Bulk selection, render and orientation payloads"
        (workspace/switch! driver "orient")
        (s/wait-visible! driver "[data-bulk-select]")
        (assert-post-forms! driver ["/orient/selection" "/orient/render"])
        (s/check! driver (str "[data-bulk-select][value='" (:weapon fixture/ids) "']"))
        (s/click! driver "[data-bulk-render-button]")
        (s/wait-visible! driver "[data-bulk-save]")
        (assert-post-forms! driver ["/orient/save"])
        (is (s/wait-until #(= 1 (get-in (s/stats driver) [:bulk :count]))))
        (orient/set-yaw! driver 45)
        (s/click! driver "[data-bulk-save-button]")
        (is (s/wait-until #(= "Saved 1 orientation." (s/text driver "#bulk-orient-status")))))
      (testing "Assembly and Paint, including forms added by swaps"
        (workspace/switch! driver "assembly")
        (workspace/await-ship! driver)
        (assert-post-forms! driver ["/assembly/hull" "/assembly/save" "/assembly/paint"])
        (s/click! driver "button:text-is('Paint assembly')")
        (s/click! driver ".paint-scheme-actions summary:text-is('New')")
        (s/fill-and-blur! driver "#paint-create input" "Transport palette")
        (s/click! driver "#paint-create button")
        (s/wait-visible! driver "#paint-material")
        (assert-post-forms! driver ["/paint/select" "/paint/create" "/paint/rename" "/paint/delete"
                                    "/paint/target" "/paint/material" "/paint/stroke" "/paint/tool" "/paint/group/create"])
        (s/check! driver "#paint-target input[value='[[:weapon 0]]']")
        (s/click! driver ".paint-group-link")
        (s/fill-and-blur! driver "#paint-group-create input[name=name]" "Transport group")
        (s/click! driver "#paint-group-create button")
        (s/wait-visible! driver "button:text-is('Rename group')")
        (assert-post-forms! driver ["/paint/group/rename" "/paint/group/members" "/paint/group/order" "/paint/group/delete"]))
      (testing "Ship Browser actions"
        (workspace/switch! driver "ships")
        (s/wait-visible! driver ".ship-card")
        (assert-post-forms! driver ["/ships/preview" "/ships/edit" "/ships/duplicate" "/ships/delete"]))
      (finally (s/quit! driver) (fixture/stop! started)))))
