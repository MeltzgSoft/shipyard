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

(defn- color-tab [state mode]
  (case mode
    :ships (or (:inspector-tab state) (when (= :editor (:view state)) "assembly"))
    :browse (case (:view state)
              :grid "orientation"
              :variant "variant"
              (or (:inspector-tab state) "part"))
    nil))

(defn mount-colors? [state mode]
  (if-let [tab (color-tab state mode)]
    (get-in state [:tab-colors tab] (if (= :ships mode) (= "assembly" tab) (not= "regions" tab)))
    (true? (:colors state))))

(defn toggle-colors [state mode]
  (if-let [tab (color-tab state mode)]
    (assoc-in state [:tab-colors tab] (not (mount-colors? state mode)))
    (update state :colors not)))

(defn select-part
  ([state part-id] (select-part state part-id false))
  ([state part-id open?]
   (cond-> (assoc state :view :part :selection part-id)
     (or open? (not= part-id (:selection state))) (assoc :inspector-tab "part"))))

(defn drawer-states [previous slots]
  (into {}
        (map (fn [{:keys [id assigned]}]
               (let [complete? (boolean (and assigned (every? :assigned (filter #(model/descendant? id (:id %)) slots))))
                     old (get previous id)]
                 [id {:complete complete? :open (if (= complete? (:complete old)) (:open old) (not complete?))}]))
             slots)))
