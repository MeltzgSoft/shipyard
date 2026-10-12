(ns shipyard.unit.fleet.plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [shipyard.fleet.plan :as plan]))

(def snapshot
  {:fleet {:fleet/library :library :fleet/entries {:a 2 :b 3}
           :fleet/extras {"weapon" 4} :fleet/variant-preference [:supported :unsupported]}
   :classes {:a {:loadout/library :library :loadout/hull "hull"
                 :loadout/slots {[:left] "weapon" [:right] "weapon" [:left :nested] "weapon"}}
             :b {:loadout/library :library :loadout/hull "hull" :loadout/slots {}}}
   :parts (into {} (for [id ["hull" "weapon"]]
                     [id {:part/id id :part/library :library :part/present? true
                          :part/sources {:unsupported {:source/key [id :unsupported]
                                                       :source/variant :unsupported :source/present? true
                                                       :source/path (str id "/unsupported.stl")
                                                       :source/size 12 :source/mtime 34}}}]))})

(deftest archive-path-test
  (testing "canonical paths preserve names and reject traversal and foreign roots"
    (is (plan/archive-path? "Bundle/Part, \"A\"/unsupported.stl"))
    (doseq [path [nil "../x.stl" "/x.stl" "a//x.stl" "a/./x.stl" "C:/x.stl" "a\\x.stl" "a/x.txt" "a/\u0000.stl"]]
      (is (not (plan/archive-path? path)) (str path)))))

(deftest requirements-test
  (testing "every saved full-path occurrence counts; extras aggregate and incomplete classes still contribute"
    (is (= {:quantities {"hull" 5 "weapon" 10} :errors []} (plan/requirements snapshot))))
  (testing "valid stored quantities aggregate beyond signed-long precision"
    (let [large (assoc-in snapshot [:fleet :fleet/entries :a] Long/MAX_VALUE)]
      (is (= (+' (*' 3 Long/MAX_VALUE) 4)
             (get-in (plan/requirements large) [:quantities "weapon"])))
      (is (:ready? (plan/print-plan large)))))
  (testing "missing and cross-library classes block readiness"
    (is (= :missing-class (-> (plan/requirements (assoc snapshot :classes {})) :errors first :code)))
    (is (= :missing-class (-> (plan/requirements (assoc-in snapshot [:classes :a :loadout/library] :other)) :errors first :code)))))

(deftest print-plan-test
  (testing "missing fleet and invalid empty snapshot never become a ready empty export"
    (doseq [s [nil {} {:fleet {}}]]
      (let [result (plan/print-plan s)]
        (is (false? (:ready? result)))
        (is (= :missing-fleet (-> result :errors first :code))))))
  (testing "fallback is per-part and includes exact source identity and stamps"
    (let [s (assoc-in snapshot [:parts "hull" :part/sources :supported]
                      {:source/key ["hull" :supported] :source/variant :supported :source/present? true
                       :source/path "hull/supported.stl" :source/size 56 :source/mtime 78})
          result (plan/print-plan s)]
      (is (:ready? result))
      (is (= [[:supported 5] [:unsupported 10]] (mapv (juxt :variant :quantity) (:files result))))
      (is (= {:size 56 :mtime 78} (:stamp (first (:files result)))))))
  (testing "an unlisted variant never falls back"
    (is (= [:unavailable-variant :unavailable-variant]
           (mapv :code (:errors (plan/print-plan (assoc-in snapshot [:fleet :fleet/variant-preference] [:supported])))))))
  (testing "missing and cross-library parts, invalid paths, and archive collisions block readiness"
    (doseq [[s code] [[(assoc snapshot :parts {}) :missing-part]
                      [(assoc-in snapshot [:parts "hull" :part/library] :other) :missing-part]
                      [(assoc-in snapshot [:parts "hull" :part/sources :unsupported :source/path] "../bad.stl") :invalid-source-path]
                      [(assoc-in snapshot [:parts "hull" :part/sources :unsupported :source/path] "weapon/unsupported.stl") :conflicting-source-path]]]
      (let [result (plan/print-plan s)]
        (is (false? (:ready? result)))
        (is (some #{code} (map :code (:errors result))))))))
