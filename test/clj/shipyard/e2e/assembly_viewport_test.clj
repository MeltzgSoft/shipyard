(ns shipyard.e2e.assembly-viewport-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.e2e.support :as s]))

(defn- request! [driver path params]
  (s/js driver
        (str "async () => { const r = await fetch(" (json/write-str path) ","
             (if params
               (str "{method:'POST',body:new URLSearchParams(" (json/write-str params) ")}")
               "{}")
             "); const h = r.headers.get('HX-Trigger'); if(h) for(const [name,value] of Object.entries(JSON.parse(h))) document.body.dispatchEvent(new CustomEvent(name,{detail:{value}})); return r.status; }")))

(defn- await-count! [driver n]
  (s/wait-until
   #(do (request! driver "/assembly?poll=1" nil)
        (= n (count (get-in (s/stats driver) [:assembly :slots]))))))

(defn- slots [driver]
  (into {} (map (fn [item]
                  [(mapv (fn [[mount ordinal]] [(keyword mount) ordinal]) (:slot item)) item]))
        (get-in (s/stats driver) [:assembly :slots])))

(deftest assembled-scene-workflow
  (let [started (fixture/start! true) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-until #(s/stats driver))
      (testing "a hull and duplicate weapons occupy distinct transforms"
        (is (= 200 (request! driver "/assembly/hull" {"revision" "0" "part-id" (:hull fixture/ids)})))
        (is (await-count! driver 1))
        (request! driver "/assembly/assign" {"revision" "1" "slot" "[[:weapon 0]]" "part-id" (:weapon fixture/ids)})
        (request! driver "/assembly/assign" {"revision" "2" "slot" "[[:weapon 1]]" "part-id" (:weapon fixture/ids)})
        (is (await-count! driver 3))
        (let [by-slot (slots driver)]
          (is (not= (get-in by-slot [[[:weapon 0]] :matrix]) (get-in by-slot [[[:weapon 1]] :matrix])))
          (is (= (get-in by-slot [[[:weapon 0]] :part-id]) (get-in by-slot [[[:weapon 1]] :part-id])))))
      (testing "nested placement and replacement preserve unrelated object identity"
        (request! driver "/assembly/assign" {"revision" "3" "slot" "[[:weapon 0] [:turret 0]]" "part-id" (:turret fixture/ids)})
        (is (await-count! driver 4))
        (let [before (slots driver)]
          (request! driver "/assembly/assign" {"revision" "4" "slot" "[[:weapon 0]]" "part-id" (:weapon-alt fixture/ids)})
          (is (await-count! driver 3))
          (is (= (get-in before [[[:weapon 1]] :uuid]) (get-in (slots driver) [[[:weapon 1]] :uuid])))
          (is (not= (get-in before [[[:weapon 0]] :uuid]) (get-in (slots driver) [[[:weapon 0]] :uuid])))))
      (testing "late fetch completion cannot resurrect a cleared slot"
        (s/js driver "() => { window.originalFetch = window.fetch; window.pendingMeshes = []; window.fetch = (url, opts) => String(url).startsWith('/mesh/') ? new Promise(resolve => window.pendingMeshes.push(() => window.originalFetch(url, opts).then(resolve))) : window.originalFetch(url, opts); }")
        (request! driver "/assembly/assign" {"revision" "5" "slot" "[[:weapon 0]]" "part-id" (:weapon fixture/ids)})
        (request! driver "/assembly/clear" {"revision" "6" "slot" "[[:weapon 0]]"})
        (s/js driver "async () => { window.fetch = window.originalFetch; await Promise.all(window.pendingMeshes.map(release => release())); }")
        (is (await-count! driver 2))
        (is (not (contains? (slots driver) [[:weapon 0]]))))
      (testing "browsing clears assembly and resuming restores only the current draft"
        (s/click! driver ".part__select:has(.part__name:text-is('prow'))")
        (s/await-part driver (:prow fixture/ids))
        (is (= [(:prow fixture/ids)] (:parts (s/stats driver))))
        (request! driver "/assembly" nil)
        (is (await-count! driver 2))
        (request! driver "/assembly/reset" {"revision" "7"})
        (is (await-count! driver 0))
        (is (s/wait-until #(<= (:geometries (s/stats driver)) 1))))
      (finally (s/quit! driver) (fixture/stop! started)))))
