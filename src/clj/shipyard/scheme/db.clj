(ns shipyard.scheme.db
  "Domain facade over the shared transactional metadata store."
  (:require [integrant.core :as ig]
            [shipyard.store.db :as store]))

(defmethod ig/init-key :shipyard.scheme/db [_ value] (assoc value :lock (get-in value [:store :lock])))

(defn snapshot! [{:keys [store]}]
  (store/read! store #(store/records-value % :schemes)))

(defn record! [{:keys [store]} id]
  (store/read! store #(store/record-value % :schemes id)))

(defn put! [{:keys [store catalog]} record mode]
  (store/put-record! store (:library @(:state catalog)) :schemes record mode))

(defn delete! [{:keys [store]} id]
  (store/delete-record! store :schemes id))

(defn listing! [{:keys [store]}]
  (store/read! store store/scheme-summaries))

(defn palette! [{:keys [store]} id]
  (store/read! store #(store/scheme-palette % id)))
