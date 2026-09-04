(ns shipyard.unit.views-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shipyard.http.htmx :as htmx]
            [shipyard.http.views :as views]))

(def ^:private hull
  #:part{:id "Human Navy Fleet Bundle/Cruiser/Hull"
         :bundle "Human Navy Fleet Bundle" :class "Cruiser" :name "Hull"
         :variants [:unsupported :unsupported-pitted]
         :renderable true :role-hint :hull :role-source :inferred})

(def ^:private supported-only
  #:part{:id "Human Navy Fleet Bundle/Cruiser/Fancy Prow"
         :bundle "Human Navy Fleet Bundle" :class "Cruiser" :name "Fancy Prow"
         :variants [:supported]
         :renderable false :role-hint :prow :role-source :inferred})

(def ^:private hull-with-mounts
  (assoc hull :part/mounts [{:mount/id :weapon-1
                             :mount/kind :socket
                             :mount/accepts #{:weapon}
                             :mount/pos [1.0 0.0 0.0]
                             :mount/axis [1.0 0.0 0.0]
                             :mount/roll [0.0 1.0 0.0]}
                            {:mount/id :plug
                             :mount/kind :plug
                             :mount/pos [0.0 0.0 -1.0]
                             :mount/axis [0.0 0.0 -1.0]
                             :mount/roll [0.0 1.0 0.0]}]))

(defn- render [hiccup] (htmx/html hiccup))

;; --- supported-only parts ---------------------------------------------------

(deftest unrenderable-parts-carry-a-reason
  (is (nil? (views/unrenderable-reason hull)))
  (is (str/includes? (views/unrenderable-reason supported-only) "supported STL"))
  (testing "a folder with no variant Shipyard can open still gets a reason"
    (is (some? (views/unrenderable-reason (assoc supported-only :part/variants []))))))

(deftest unrenderable-parts-are-greyed-not-hidden
  (let [html (render (views/library-results [hull supported-only]))]
    (testing "both parts are listed"
      (is (str/includes? html "Hull"))
      (is (str/includes? html "Fancy Prow")))
    (testing "the unpreviewable one is marked and says why"
      (is (str/includes? html "part--unrenderable"))
      (is (str/includes? html "supported STL")))
    (testing "and the renderable one is not"
      (is (= 1 (count (re-seq #"part--unrenderable" html)))))))

(deftest counts-are-reported
  (is (str/includes? (render (views/library-results [])) "No parts match"))
  (is (str/includes? (render (views/library-results [hull])) "1 part"))
  (is (str/includes? (render (views/library-results [hull supported-only])) "2 parts")))

;; --- links ------------------------------------------------------------------

(deftest cards-link-to-percent-encoded-ids
  (let [html (render (views/part-card hull))]
    (is (str/includes? html "hx-get=\"/part/Human%20Navy%20Fleet%20Bundle/Cruiser/Hull\""))
    (is (str/includes? html "hx-target=\"#detail\""))))

(deftest guessed-roles-are-styled-as-guesses
  (testing "roughly one part in ten is labelled wrong; the style is the disclaimer"
    (is (str/includes? (render (views/part-card hull)) "part__role--guessed"))
    (is (not (str/includes? (render (views/part-card (assoc hull :part/role-source :class)))
                            "part__role--guessed")))))

;; --- the canvas island ------------------------------------------------------

(def ^:private every-view
  "Every fragment the server can send, so the canvas rule can be asserted over
  all of them at once rather than one at a time."
  [(views/shell {:bundles ["A"] :classes ["Cruiser"] :roles [:hull :prow]} "/lib")
   (views/library-results [hull supported-only])
   (views/library-needs-root)
   (views/library-unavailable "/nowhere")
   (views/detail-empty)
   (views/detail-missing "no/such/part")
   (views/detail-preparing hull)
   (views/detail-ready hull (apply str (repeat 64 "1")))
   (views/detail-failed hull "not a usable STL")
   (views/detail-unrenderable supported-only (views/unrenderable-reason supported-only))])

(deftest the-canvas-is-preserved-and-never-a-swap-target
  (let [shell (render (views/shell {:bundles [] :classes [] :roles []} "/lib"))]
    (testing "hx-preserve keeps the WebGL context alive across every swap"
      (is (re-find #"<canvas[^>]*id=\"viewport\"" shell))
      (is (re-find #"<canvas[^>]*hx-preserve=\"true\"" shell))))
  (testing "nothing anywhere aims a swap at it"
    (doseq [v every-view]
      (let [html (render v)]
        (is (not (re-find #"hx-target=\"#viewport\"" html)) html)
        (is (not (re-find #"hx-select[^=]*=\"#viewport\"" html)) html)
        (is (not (re-find #"hx-swap-oob[^>]*viewport" html)) html)))))

;; --- the shell --------------------------------------------------------------

(deftest the-shell-carries-the-filters
  (let [html (render (views/shell {:bundles ["Human Navy Fleet Bundle"]
                                   :classes ["Cruiser" "Escort"]
                                   :roles   [:hull :prow]}
                                  "/lib"))]
    (testing "all four filter parameters from the route table are present"
      (doseq [p ["bundle" "class" "role" "q"]]
        (is (str/includes? html (str "name=\"" p "\"")) p)))
    (testing "the menus are built from the catalog, not hard-coded"
      (is (str/includes? html "Human Navy Fleet Bundle"))
      (is (str/includes? html "Escort"))
      (is (str/includes? html ">prow<")))
    (testing "and the list loads itself on first paint"
      (is (str/includes? html "load from:body")))))

(deftest the-results-fragment-does-not-refetch-itself
  (testing "the shell's copy of #library-results triggers on load; the fragment
            that replaces it must not, or the panel loops forever"
    (is (not (str/includes? (render (views/library-results [hull])) "hx-trigger")))))

(deftest preparing-polls-and-ready-does-not
  (is (str/includes? (render (views/detail-preparing hull)) "load delay:"))
  (is (not (str/includes? (render (views/detail-ready hull (apply str (repeat 64 "1")))) "hx-trigger")))
  (testing "a failure is shown rather than retried behind the user's back"
    (let [html (render (views/detail-failed hull "not a usable STL"))]
      (is (not (str/includes? html "load delay:")))
      (is (str/includes? html "retry=1")))))

(deftest configured-interface-legend-matches-mount-types
  (let [html (render (views/detail-ready hull-with-mounts (apply str (repeat 64 "1"))))]
    (is (str/includes? html "Part metadata"))
    (is (str/includes? html "hx-post=\"/parts/role\""))
    (is (str/includes? html "Interface colors"))
    (is (str/includes? html "plug"))
    (is (str/includes? html "weapon socket"))
    (is (str/includes? html "hx-post=\"/mounts/edit\""))
    (is (str/includes? html ">Edit<"))
    (is (str/includes? html "--interface-color:#69d2c0"))
    (is (str/includes? html "--interface-color:#ff7a90"))
    (is (str/includes? html "data-interface-mounts"))))

(deftest mount-errors-stay-inside-the-loaded-detail
  (testing "save errors keep the selected face form and a dismiss action"
    (let [html (render (views/detail-ready hull
                                           (apply str (repeat 64 "1"))
                                           {:preview {:part hull
                                                      :frame {:mount/pos [0 0 0]
                                                              :mount/axis [0 0 1]
                                                              :mount/roll [1 0 0]}
                                                      :values {:mount-id "mount-1"}
                                                      :error "Already exists"}}))]
      (is (str/includes? html "Already exists"))
      (is (str/includes? html "Dismiss"))
      (is (str/includes? html "name=\"mount-id\""))
      (is (str/includes? html "value=\"mount-1\""))
      (is (str/includes? html "Normal (+Z)"))
      (is (str/includes? html "Roll (+X)"))
      (is (str/includes? html "Up (+Y)"))
      (is (not (str/includes? html "class=\"mount-wizard__field\">Part role")))
      (is (str/includes? html "Pick mount face"))))
  (testing "delete errors keep the part detail rather than replacing it"
    (let [html (render (views/detail-ready hull
                                           (apply str (repeat 64 "1"))
                                           {:error "No mount with that id exists."}))]
      (is (str/includes? html "No mount with that id exists."))
      (is (str/includes? html "Dismiss"))
      (is (str/includes? html "Pick mount face")))))
