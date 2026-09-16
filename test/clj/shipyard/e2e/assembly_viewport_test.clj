(ns shipyard.e2e.assembly-viewport-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.http.urls :as urls]
            [shipyard.e2e.support :as s]))

(defn- request! [driver path params]
  (s/js driver
        (str "async () => { let status; const capture = e => { status = e.detail.xhr.status; };"
             "document.body.addEventListener('htmx:afterRequest', capture);"
             "try { await htmx.ajax(" (json/write-str (if params "POST" "GET")) ","
             (json/write-str path) ", {target:'#detail', swap:'innerHTML', values:"
             (json/write-str (or params {})) "}); return status; }"
             "finally { document.body.removeEventListener('htmx:afterRequest', capture); } }")))

(defn- await-count! [driver n]
  (s/wait-until
   #(do (request! driver "/assembly?poll=1" nil)
        (= n (count (get-in (s/stats driver) [:assembly :slots]))))))

(defn- slots [driver]
  (into {} (map (fn [item]
                  [(mapv (fn [[mount ordinal]] [(keyword mount) ordinal]) (:slot item)) item]))
        (get-in (s/stats driver) [:assembly :slots])))

(defn- assert-visible-assembly! [driver]
  (is (s/wait-until #(= (:hull fixture/ids) (get-in (slots driver) [[] :part-id])))
      "the resumed hull should load without issuing a test-only assembly request")
  (let [rail (s/bounds driver "#library")
        viewport (s/bounds driver "#viewport")]
    (is (<= (+ (:x rail) (:width rail)) (inc (:x viewport)))
        (str "the rail must sit beside the viewport, not cover it: " {:rail rail :viewport viewport}))
    (is (> (:width viewport) 500) "assembly should retain usable viewport space"))
  (is (true? (s/js driver "() => { const canvas = document.querySelector('#viewport'); const r = canvas.getBoundingClientRect(); return document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2) === canvas; }"))
      "the rendered canvas must be exposed to the user, not hidden under another panel"))

(deftest assembly-viewport-survives-orient-mode-transitions
  (let [started (fixture/start! true) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-visible! driver "#library-results .part")
      (s/click! driver ".masthead a:has-text('Assemble')")
      (s/wait-visible! driver ".assembly__hull")
      (s/select-option! driver ".assembly__hull select[name=part-id]" "hull")
      (s/click! driver ".assembly__hull button")
      (assert-visible-assembly! driver)
      (doseq [render-grid? [false true]]
        (testing (if render-grid? "return from Orient previews" "return from the Orient table")
          (s/click! driver ".masthead a:has-text('Orient')")
          (s/wait-visible! driver "[data-bulk-select]")
          (when render-grid?
            (s/check! driver (str "[data-bulk-select][value='" (:hull fixture/ids) "']"))
            (s/click! driver "[data-bulk-render-button]")
            (is (s/wait-until #(= 1 (get-in (s/stats driver) [:bulk :count])))))
          (s/click! driver ".masthead a:has-text('Assemble')")
          (s/wait-visible! driver ".assembly__hull")
          (assert-visible-assembly! driver)
          (is (zero? (s/count-els driver "[data-bulk-grid]")))
          (is (zero? (get-in (s/stats driver) [:bulk :count])))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest large-assembly-updates-use-the-response-body
  ;; Real catalog paths can be long and contain characters escaped in HTML.
  ;; Exercise both the old header ceiling and a lossless body round trip.
  (with-redefs [fixture/ids (update-vals fixture/ids
                                         #(str % (apply str (repeat 9 "-long-catalog-variant")) "-艦&detail"))]
    (let [started (fixture/start! true) driver (s/make-driver)
          revision (atom 0)
          assign (fn [path part]
                   (is (= 200 (request! driver "/assembly/assign"
                                        {"revision" (str @revision) "slot" (pr-str path)
                                         "part-id" (get fixture/ids part)})))
                   (swap! revision inc))]
      (try
        (s/go! driver (s/base-url (:system started)))
        (is (s/wait-until #(s/stats driver)))
        (s/click! driver ".masthead [data-workspace-mode='assembly']")
        (s/wait-visible! driver ".assembly__hull")
        (s/js driver "() => { window.assemblyResponses = []; document.body.addEventListener('htmx:afterRequest', e => { const xhr = e.detail.xhr; const doc = new DOMParser().parseFromString(xhr.responseText, 'text/html'); const field = doc.querySelector('[data-assembly-event]'); if (field) window.assemblyResponses.push({status:xhr.status, headers:xhr.getAllResponseHeaders().length, trigger:xhr.getResponseHeader('HX-Trigger'), size:field.getAttribute('data-assembly-event').length}); }); }")
        (is (= 200 (request! driver "/assembly/hull"
                             {"revision" "0" "part-id" (:hull fixture/ids)})))
        (swap! revision inc)
        (is (await-count! driver 1))
        (doseq [[path part] [[[[:prow 0]] :prow] [[[:bridge 0]] :bridge]
                             [[[:antenna 0]] :antenna] [[[:antenna 1]] :antenna]
                             [[[:weapon 0]] :weapon] [[[:weapon 1]] :weapon]
                             [[[:mirrored-weapon 0]] :weapon] [[[:mirrored-weapon 1]] :weapon]
                             [[[:weapon 0] [:turret 0]] :turret]
                             [[[:weapon 1] [:turret 0]] :turret]
                             [[[:mirrored-weapon 0] [:turret 0]] :turret]
                             [[[:mirrored-weapon 1] [:turret 0]] :turret]]]
          (assign path part))
        (is (await-count! driver 13))
        (testing "repeated replacements still render after exceeding the old header limit"
          (doseq [part (take 6 (cycle [:prow-alt :prow]))]
            (assign [[:prow 0]] part)
            (is (s/wait-until #(= (get fixture/ids part)
                                  (get-in (slots driver) [[[:prow 0]] :part-id]))))))
        (testing "large body payloads leave response headers small"
          (let [responses (s/js driver "() => window.assemblyResponses")]
            (is (> (apply max (map :size responses)) 8192))
            (is (every? #(= 200 (:status %)) responses))
            (is (every? #(nil? (:trigger %)) responses))
            (is (every? #(< (:headers %) 2048) responses))))
        (testing "a stale response is rendered and its body event is consumed"
          (is (= 409 (request! driver "/assembly/clear" {"revision" "0" "slot" "[[:prow 0]]"})))
          (is (await-count! driver 13)))
        (is (= 200 (request! driver "/assembly/clear"
                             {"revision" (str @revision) "slot" "[[:prow 0]]"})))
        (swap! revision inc)
        (is (await-count! driver 12))
        (is (= 200 (request! driver "/assembly/reset" {"revision" (str @revision)})))
        (is (await-count! driver 0))
        (is (zero? (s/count-els driver "#detail [data-assembly-event]")))
        (finally (s/quit! driver) (fixture/stop! started))))))

(deftest mount-drawers-keep-disclosure-state-across-updates
  (let [started (fixture/start! true) driver (s/make-driver)
        drawer (fn [path] (str "#library details[data-slot='" (pr-str path) "']"))
        wrapper (fn [path] (str "#library .assembly__slot-wrap:has(> details[data-slot='" (pr-str path) "'])"))
        open? (fn [path]
                (s/js driver (str "() => document.querySelector(" (json/write-str (drawer path)) ").open")))
        choose! (fn [path part]
                  (s/click! driver (str (drawer path) " button[name='part-id'][value='" (get fixture/ids part) "']")))]
    (try
      (s/go! driver (s/base-url (:system started)))
      (is (s/wait-until #(s/stats driver)))
      (s/click! driver ".masthead [data-workspace-mode='assembly']")
      (s/wait-visible! driver ".assembly__hull")
      (request! driver "/assembly/hull" {"revision" "0" "part-id" (:hull fixture/ids)})
      (is (await-count! driver 1))
      (s/click! driver (str (drawer [[:bridge 0]]) " summary"))
      (s/click! driver (str (drawer [[:antenna 0]]) " summary"))
      (choose! [[:weapon 0]] :weapon)
      (is (await-count! driver 2))
      (testing "incomplete subtrees stay open while unrelated disclosure state is retained"
        (is (false? (open? [[:bridge 0]])))
        (is (false? (open? [[:antenna 0]])))
        (is (true? (open? [[:antenna 1]])))
        (is (true? (open? [[:weapon 0]])))
        (is (true? (open? [[:weapon 0] [:turret 0]]))))
      (choose! [[:weapon 0] [:turret 0]] :turret)
      (is (await-count! driver 3))
      (testing "completing a child collapses the whole completed subtree"
        (is (false? (open? [[:weapon 0]])))
        (is (false? (open? [[:weapon 0] [:turret 0]])))
        (is (= "turret" (s/text driver (str (drawer [[:weapon 0]]) " > summary .assembly__mount-subpart-name"))))
        (is (not= "none" (s/js driver (str "() => getComputedStyle(document.querySelector("
                                           (json/write-str (str (drawer [[:weapon 0]]) " > summary .assembly__mount-subpart-dot"))
                                           ")).backgroundColor")))))
      (choose! [[:weapon 1]] :weapon)
      (is (await-count! driver 4))
      (testing "nested paths are distinct even when their mount names match"
        (is (false? (open? [[:weapon 0]])))
        (is (true? (open? [[:weapon 1]])))
        (is (true? (open? [[:weapon 1] [:turret 0]]))))
      (s/click! driver (str (drawer [[:weapon 0]]) " > summary"))
      (choose! [[:weapon 0]] :weapon-alt)
      (is (s/wait-until #(= (:weapon-alt fixture/ids) (get-in (slots driver) [[[:weapon 0]] :part-id]))))
      (is (true? (open? [[:weapon 0]])))
      (is (true? (open? [[:weapon 0] [:turret 0]])))
      (is (zero? (s/count-els driver (str (drawer [[:weapon 0]]) " > summary .assembly__mount-subpart"))))
      (s/click! driver (str (wrapper [[:weapon 0]]) " > .assembly__mount-actions button"))
      (is (await-count! driver 2))
      (is (zero? (s/count-els driver (drawer [[:weapon 0] [:turret 0]]))))
      (is (false? (open? [[:bridge 0]])))
      (testing "reset removes previous disclosure state"
        (is (= 200 (request! driver "/assembly/reset" {"revision" "6"})))
        (is (= 200 (request! driver "/assembly/hull" {"revision" "7" "part-id" (:hull fixture/ids)})))
        (is (await-count! driver 1))
        (is (true? (open? [[:bridge 0]]))))
      (finally (s/quit! driver) (fixture/stop! started)))))

(deftest assembled-scene-workflow
  (let [started (fixture/start! true) driver (s/make-driver)]
    (try
      (s/go! driver (s/base-url (:system started)))
      (s/wait-until #(s/stats driver))
      (s/click! driver ".masthead [data-workspace-mode='assembly']")
      (s/wait-visible! driver ".assembly__hull")
      (testing "a hull and duplicate weapons occupy distinct transforms"
        (is (= 200 (request! driver "/assembly/hull" {"revision" "0" "part-id" (:hull fixture/ids)})))
        (is (await-count! driver 1))
        (request! driver "/assembly/assign" {"revision" "1" "slot" "[[:weapon 0]]" "part-id" (:weapon fixture/ids)})
        (request! driver "/assembly/assign" {"revision" "2" "slot" "[[:weapon 1]]" "part-id" (:weapon fixture/ids)})
        (is (await-count! driver 3))
        (let [by-slot (slots driver)]
          (is (not= (get-in by-slot [[[:weapon 0]] :matrix]) (get-in by-slot [[[:weapon 1]] :matrix])))
          (is (= (get-in by-slot [[[:weapon 0]] :part-id]) (get-in by-slot [[[:weapon 1]] :part-id]))))
        (testing "mount colors can be hidden and restored without changing the assembly"
          (is (true? (:mount-colors-enabled (s/stats driver))))
          (is (> (count (set (:materials (s/stats driver)))) 1))
          (is (pos? (:visible-mount-markers (s/stats driver))))
          (s/click! driver "[data-mount-colors-toggle]")
          (is (s/wait-until #(false? (:mount-colors-enabled (s/stats driver)))))
          (is (every? #{"9aa4af"} (:materials (s/stats driver))))
          (is (zero? (:visible-mount-markers (s/stats driver))))
          (s/click! driver "[data-mount-colors-toggle]")
          (is (s/wait-until #(true? (:mount-colors-enabled (s/stats driver)))))
          (is (> (count (set (:materials (s/stats driver)))) 1))
          (is (pos? (:visible-mount-markers (s/stats driver))))))
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
      (testing "browsing restores its own model and resuming restores the current draft"
        (s/click! driver ".masthead [data-workspace-mode='browse']")
        (s/wait-visible! driver "#library-results .part")
        (request! driver (urls/part-url (:prow fixture/ids)) nil)
        (s/await-part driver (:prow fixture/ids))
        (is (= [(:prow fixture/ids)] (:parts (s/stats driver))))
        (s/click! driver ".masthead [data-workspace-mode='assembly']")
        (s/wait-visible! driver ".assembly__hull")
        (is (await-count! driver 2))
        (let [before (:geometries (s/stats driver))]
          (request! driver "/assembly/reset" {"revision" "7"})
          (is (await-count! driver 0))
          (is (s/wait-until #(<= (:geometries (s/stats driver)) (- before 2)))))
        (s/click! driver ".masthead [data-workspace-mode='browse']")
        (s/await-part driver (:prow fixture/ids)))
      (finally (s/quit! driver) (fixture/stop! started)))))
