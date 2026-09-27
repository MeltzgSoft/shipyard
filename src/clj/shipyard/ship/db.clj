(ns shipyard.ship.db
  "Named-ship facade over the shared transactional metadata store."
  (:require [integrant.core :as ig]
            [shipyard.store.db :as store]))

(defmethod ig/init-key :shipyard.ship/db [_ value] (assoc value :lock (get-in value [:store :lock])))

(defn snapshot! [{:keys [store]}]
  (store/read! store #(store/records-value % :ships)))

(defn record! [{:keys [store]} id]
  (store/read! store #(store/record-value % :ships id)))

(defn put! [{:keys [store catalog]} record mode]
  (store/put-record! store (:library @(:state catalog)) :ships record mode))

(defn delete! [{:keys [store]} id]
  (store/delete-record! store :ships id))

(defn listing! [{:keys [store]}]
  (store/read! store store/ship-summaries))
