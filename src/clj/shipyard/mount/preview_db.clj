(ns shipyard.mount.preview-db
  "Bounded transient draft sequencing; no authoring or output publication."
  (:require [integrant.core :as ig]))

(defn advance! [{:keys [state]} owner sequence cancelled?]
  (swap! state
         (fn [owners]
           (let [previous (get owners owner)
                 owners (if (> sequence (or (:sequence previous) -1))
                          (assoc owners owner {:sequence sequence :cancelled? cancelled? :access (System/nanoTime)})
                          owners)]
             (if (> (count owners) 128)
               (dissoc owners (key (apply min-key (comp :access val) owners)))
               owners)))))

(defn current?! [{:keys [state]} owner sequence]
  (let [value (get @state owner)]
    (and (= sequence (:sequence value)) (not (:cancelled? value)))))

(defmethod ig/init-key :shipyard.mount.preview-db/drafts [_ _] {:state (atom {})})
(defmethod ig/halt-key! :shipyard.mount.preview-db/drafts [_ {:keys [state]}] (reset! state {}))
