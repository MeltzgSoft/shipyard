(ns shipyard.e2e.picked-face-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.e2e.support :as s]
            [shipyard.math :as math]
            [shipyard.picked-face-fixture :as parts])
  (:import [com.microsoft.playwright APIResponse Page Route Route$FulfillOptions]
           [java.util.function Consumer]))

(defn point! [driver position]
  (let [{:keys [camera target]} (s/stats driver)
        {:keys [x y width height]} (s/bounds driver "#viewport")
        forward (math/normalize (math/subtract target camera))
        right (math/normalize (math/cross forward [0 1 0]))
        up (math/cross right forward)
        delta (math/subtract position camera)
        scale (* (math/dot delta forward) (Math/tan (/ (* 45 Math/PI) 360)))]
    [(+ x (* 0.5 width (+ 1 (/ (math/dot delta right) (* scale (/ width height))))))
     (+ y (* 0.5 height (- 1 (/ (math/dot delta up) scale))))]))

(defn pick! [driver position]
  (let [[x y] (point! driver position)] (s/click-point! driver x y)))

(defn fulfill! [{:keys [route response]}]
  (.fulfill ^Route route (doto (Route$FulfillOptions.) (.setResponse ^APIResponse response))))

(defn capture! [^Route route]
  (let [response (.fetch route)]
    {:route route :response response :headers (into {} (.headers response)) :body (.text response)}))

(defn trace-completions! [driver]
  ;; Observe completion after the app's text/DOM promise chain. The original
  ;; fetch and response bytes are retained; routes still call the real server.
  (s/js driver "() => { const fetch=window.fetch; window.facetReads=0; window.facetEvents=0; document.body.addEventListener('shipyard:facet-preview',()=>window.facetEvents++); window.fetch=(...args)=>fetch(...args).then(response=>{ if(args[0]==='/facet'){const text=response.text.bind(response); response.text=()=>text().then(body=>{setTimeout(()=>window.facetReads++,0);return body;});} return response;}); }"))

(defn reads! [driver n]
  (is (s/wait-until #(= n (s/js driver "() => window.facetReads")))))

(deftest dense-picks-render-immediately-and-match-saved-faces
  (s/assert-bundle!)
  (let [started (fixture/start! true parts/build! (fn [_])) sys (:system started)
        cat (:shipyard.catalog/db sys) driver (s/make-driver) ^Page page (:page driver)
        responses (atom [])]
    (.route page "**/facet" (reify Consumer (accept [_ route]
                                              (let [response (capture! route)]
                                                (swap! responses conj response) (fulfill! response)))))
    (try
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "Dense Mount Plate") (s/await-part driver parts/id)
      (s/click! driver "[data-detail-tab=mounts]")
      (testing "a body-carried first pick builds the actual highlight without a later swap"
        (pick! driver parts/dense-point)
        (s/wait-visible! driver ".mount-wizard__form")
        (is (nil? (get-in (last @responses) [:headers "hx-trigger"])))
        (is (str/includes? (:body (last @responses)) "data-viewport-events="))
        (is (s/wait-until #(= 800 (get-in (s/stats driver) [:preview :triangles])) 3000))
        (is (zero? (s/count-els driver "#facet-preview [data-viewport-events]"))))
      (s/click! driver ".mount-wizard__actions button[value=create]")
      (is (s/wait-until #(= 800 (get-in (s/stats driver) [:interfaces :items 0 :triangles]))))
      (let [saved (first (:part/mounts (:part (catalog/part-context! cat parts/id))))]
        (testing "removing a saved mount and repicking immediately restores the same preview"
          (s/click! driver (str "form:has(input[name=mount-id][value='" (name (:mount/id saved)) "']) button:text-is('Delete')"))
          (is (s/wait-until #(empty? (:part/mounts (:part (catalog/part-context! cat parts/id))))))
          (pick! driver parts/dense-point)
          (s/wait-visible! driver ".mount-wizard__form")
          (is (s/wait-until #(= (get-in saved [:mount/facet :indices]) (get-in (s/stats driver) [:preview :facet-indices])) 3000)))
        (testing "body and header selections replace each other without replaying stale geometry"
          (pick! driver parts/small-point)
          (is (s/wait-until #(= 2 (get-in (s/stats driver) [:preview :triangles]))))
          (is (some? (get-in (last @responses) [:headers "hx-trigger"])))
          (pick! driver parts/dense-point)
          (is (s/wait-until #(= 800 (get-in (s/stats driver) [:preview :triangles])) 3000))
          (is (zero? (s/count-els driver "#facet-preview [data-viewport-events]")))
          (s/select-option! driver ".mount-wizard__form select[name=kind]" "socket")
          (is (= 800 (get-in (s/stats driver) [:preview :triangles])))
          (s/screenshot-el! driver "#viewport" (io/file "/tmp/shipyard-dense-picked-face.png")))
        (s/click! driver ".mount-wizard__actions button[value=create]")
        (is (s/wait-until #(= (get-in saved [:mount/facet :indices]) (get-in (s/stats driver) [:interfaces :items 0 :facet-indices]))))
        (s/go! driver (s/base-url sys))
        (s/open-part! driver "Dense Mount Plate") (s/await-part driver parts/id)
        (is (= (get-in saved [:mount/facet :indices]) (get-in (s/stats driver) [:interfaces :items 0 :facet-indices]))))
      (finally (.unroute page "**/facet") (s/quit! driver) (fixture/stop! started)))))

(deftest delayed-picks-cannot-replace-newer-faces-or-activations
  (s/assert-bundle!)
  (let [started (fixture/start! true parts/build! (fn [_])) sys (:system started)
        driver (s/make-driver) ^Page page (:page driver) held (atom nil) hold? (atom true)]
    (.route page "**/facet" (reify Consumer (accept [_ route]
                                              (let [response (capture! route)]
                                                (if (compare-and-set! hold? true false)
                                                  (reset! held response) (fulfill! response))))))
    (try
      (s/go! driver (s/base-url sys))
      (s/open-part! driver "Dense Mount Plate") (s/await-part driver parts/id)
      (s/click! driver "[data-detail-tab=mounts]")
      (trace-completions! driver)
      (testing "a delayed dense response cannot replace a later small-face selection"
        (pick! driver parts/dense-point)
        (is (s/wait-until #(do (s/stats driver) (some? @held))))
        (pick! driver parts/small-point)
        (is (s/wait-until #(= 2 (get-in (s/stats driver) [:preview :triangles]))))
        (fulfill! @held) (reads! driver 2)
        (is (= 2 (get-in (s/stats driver) [:preview :triangles])))
        (is (= 1 (s/js driver "() => window.facetEvents")))
        (is (zero? (s/count-els driver "[data-viewport-events]"))))
      (testing "leaving and returning to the same authoring tab invalidates its pending pick"
        (reset! hold? true) (reset! held nil)
        (pick! driver parts/dense-point)
        (is (s/wait-until #(do (s/stats driver) (some? @held))))
        (s/click! driver "[data-detail-tab=part]")
        (s/click! driver "[data-detail-tab=mounts]")
        (fulfill! @held) (reads! driver 3)
        (is (nil? (:preview (s/stats driver))))
        (is (zero? (s/count-els driver ".mount-wizard__form"))))
      (testing "a pending response cannot restore a form after another part activation"
        (reset! hold? true) (reset! held nil)
        (pick! driver parts/dense-point)
        (is (s/wait-until #(do (s/stats driver) (some? @held))))
        (s/open-part! driver "Other Mount Plate") (s/await-part driver parts/other-id)
        (fulfill! @held) (reads! driver 4)
        (is (= parts/other-id (first (:parts (s/stats driver)))))
        (is (nil? (:preview (s/stats driver))))
        (is (zero? (s/count-els driver ".mount-wizard__form")))
        (is (zero? (s/count-els driver "[data-viewport-events]"))))
      (finally (.unroute page "**/facet") (s/quit! driver) (fixture/stop! started)))))
