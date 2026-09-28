(ns shipyard.loadout.thumbnail-test
  (:require [clojure.test :refer [deftest is]]
            [shipyard.geom :as geom]
            [shipyard.loadout.thumbnail :as t]
            [shipyard.paint.faces :as faces]
            [shipyard.scheme.material :as material]))

(deftest transformed-instances-and-source-space-colors
  (let [vertices [[0 0 0] [1 0 0] [0 1 0]] key (faces/face-key vertices)
        mesh {:positions (vec (flatten vertices)) :indices [0 1 2]}
        appearance {:material material/neutral :regions {key "Secondary"}
                    :layers {"Secondary" {:base [0 1 0]}}}
        result (t/assembled-mesh [{:mesh mesh :matrix geom/identity-matrix :appearance appearance}
                                  {:mesh mesh :matrix (assoc geom/identity-matrix 12 10.0)
                                   :appearance (assoc appearance :details {key (assoc material/neutral :base [1 0 0])})}])]
    (is (= [0 1 2 3 4 5] (:indices result)))
    (is (= [10.0 0.0 0.0 11.0 0.0 0.0 10.0 1.0 0.0] (subvec (:positions result) 9)))
    (is (= [[0 1 0] [1 0 0]] (:colors result)))
    (is (= {:positions [] :indices [] :colors []} (t/assembled-mesh [])))))

(deftest appearance-honors-source-identity-and-instance-precedence
  (let [part {:part/id "hull" :part/role-hint :hull
              :part/paint-regions {:mesh-key "current" :faces {"face" "Secondary"}}}
        profile {:scheme/layers {"Primary" material/neutral "Secondary" {:base [0 1 0]}}
                 :scheme/details {[] {:part-id "hull" :mesh-key "current" :faces {"face" (assoc material/neutral :base [1 0 0])}}}}]
    (is (= {"face" "Secondary"} (:regions (t/appearance part profile [] "current"))))
    (is (= {"face" (assoc material/neutral :base [1 0 0])} (:details (t/appearance part profile [] "current"))))
    (is (nil? (:regions (t/appearance part profile [] "changed"))))
    (is (nil? (:details (t/appearance part profile [] "changed"))))
    (is (nil? (:details (t/appearance (assoc part :part/id "replacement") profile [] "current"))))
    (let [result (t/appearance part (assoc profile :scheme/instances {[] {:part-id "hull" :material {:base [0 0 1]}}}) [] "current")]
      (is (= [0 0 1] (get-in result [:material :base])))
      (is (nil? (:regions result))))))
