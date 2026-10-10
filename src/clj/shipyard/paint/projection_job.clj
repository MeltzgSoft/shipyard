(ns shipyard.paint.projection-job
  "Binary source/revision-bound appearance snapshots on shared workers."
  (:require [shipyard.paint.faces :as faces]
            [shipyard.paint.projection :as projection]
            [shipyard.preparation :as preparation]))

(defn ordered-keys
  [{:keys [^floats positions ^ints indices index-count]}]
  (mapv (fn [triangle]
          (faces/face-key
           (mapv (fn [corner]
                   (let [offset (* 3 (aget indices (+ (* 3 triangle) corner)))]
                     (mapv #(double (aget positions (+ offset %))) (range 3)))) (range 3))))
        (range (quot (or index-count (alength indices)) 3))))

(defn appearance
  "Ordinal masks retain duplicate geometric identities and omit stale masks."
  [keys mesh-key regions details]
  (let [region-faces (when (= mesh-key (:mesh-key regions)) (:faces regions))
        detail-faces (when (= mesh-key (:mesh-key details)) (:faces details))
        layers (into ["Primary"] (sort (disj (set (vals region-faces)) "Primary")))
        materials (into [nil] (distinct (keep #(get detail-faces %) keys)))
        layer-indices (zipmap layers (range)) material-indices (zipmap materials (range))]
    {:metadata {:mesh-key mesh-key :region-revision (str (or (:revision regions) 0))
                :layer-table layers :detail-table materials}
     :layers (mapv #(get layer-indices (get region-faces % "Primary") 0) keys)
     :details (mapv #(get material-indices (get detail-faces %) 0) keys)}))

(defn- keys! [service mesh-key]
  (let [keys (ordered-keys (preparation/read-mesh! service mesh-key 0))]
    {:value keys :size (* 240 (count keys))}))

(defn build! [service mesh-key regions details]
  (let [keys (preparation/cached-source! service [:render-identities 1 mesh-key] keys! [service mesh-key])
        {:keys [metadata layers details]} (appearance keys mesh-key regions details)]
    {:bytes (projection/encode metadata layers details) :content-type "application/vnd.shipyard.appearance"}))

(defn request! [service {:keys [part-id mesh-key regions details]}]
  (when (and service mesh-key)
    (let [details (when (= part-id (:part-id details)) details)]
      (select-keys
       (preparation/request! service {:key [:appearance 1 mesh-key regions details]
                                      :retained-bytes (* 256 (+ (count (:faces regions)) (count (:faces details))))
                                      :part-id part-id :mesh-key mesh-key :run! build!
                                      :args [service mesh-key regions details]})
       [:state :resource :message]))))

(defn pack!
  "Replace full masks with one shared binary resource. Small deltas stay inline."
  [service event placements]
  (if-not service event
          (update event :commands
                  (fn [commands]
                    (mapv
                     (fn [{:keys [slot op changes] :as command}]
                       (let [value (if (= op :paint) changes command)
                             full? (or (= op :set) (contains? value :regions)
                                       (> (+ (count (get-in value [:detail-delta :patch :replace]))
                                             (count (get-in value [:detail-delta :patch :set]))
                                             (count (get-in value [:detail-delta :patch :remove]))) 256)
                                       (and (contains? value :details) (seq (get-in value [:details :faces]))))
                             placement (get placements slot)]
                         (if (and full? placement)
                           (let [ref (when (or (seq (get-in placement [:regions :faces]))
                                               (seq (get-in placement [:details :faces])))
                                       (request! service placement))
                                 pack #(-> %
                                           (dissoc :detail-delta)
                                           (assoc :appearance-ref ref
                                                  :regions (when-let [regions (:regions placement)]
                                                             (assoc (dissoc regions :faces) :revision-token (str (:revision regions))))
                                                  :details (some-> (:details placement) (dissoc :faces))))]
                             (if (= op :paint) (update command :changes pack) (pack command)))
                           command))) commands)))))
