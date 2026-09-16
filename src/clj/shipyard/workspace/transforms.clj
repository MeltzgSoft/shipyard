(ns shipyard.workspace.transforms
  "Pure workspace view projection and request ordering."
  (:require [clojure.walk :as walk]
            [shipyard.assembly.model :as model]))

(defn selected-filters
  "Project stored filter values into ordinary form controls."
  [form filters]
  (walk/postwalk
   (fn [node]
     (if (and (vector? node) (map? (second node)) (contains? filters (:name (second node))))
       (let [[tag attrs & children] node value (get filters (:name attrs))]
         (case tag
           :input (into [tag (assoc attrs :value value)] children)
           :select (into [tag attrs]
                         (walk/postwalk (fn [child]
                                          (if (and (vector? child) (= :option (first child)))
                                            (assoc-in child [1 :selected] (= value (get-in child [1 :value]))) child)) children))
           node))
       node)) form))

(defn current-request? [activation request-activation workspace owner]
  (and (= request-activation activation) (or (nil? owner) (= workspace owner))))

(defn drawer-states [previous slots]
  (into {}
        (map (fn [{:keys [id assigned]}]
               (let [complete? (boolean (and assigned (every? :assigned (filter #(model/descendant? id (:id %)) slots))))
                     old (get previous id)]
                 [id {:complete complete? :open (if (= complete? (:complete old)) (:open old) (not complete?))}]))
             slots)))
