(ns darelwasl.db
  (:require [clojure.tools.logging :as log]
            [datomic.client.api :as d])
  (:import (java.util UUID)))

(defn- ensure-config
  [{:keys [storage-dir system db-name] :as cfg}]
  (when (or (nil? storage-dir) (nil? system) (nil? db-name))
    (throw (ex-info "Missing Datomic config" {:config cfg})))
  cfg)

(defn connect!
  "Start a Datomic dev-local client + connection based on config map with keys
  {:storage-dir :system :db-name}. Creates the database if it does not exist.
  Returns {:client .. :conn ..} on success, {:error ...} on failure."
  [{:keys [storage-dir system db-name] :as cfg}]
  (try
    (let [{:keys [storage-dir system db-name]} (ensure-config cfg)
          client (d/client {:server-type :dev-local
                            :storage-dir storage-dir
                            :system system})]
      (d/create-database client {:db-name db-name})
      (log/infof "Connected to Datomic dev-local system '%s' db '%s' (storage %s)"
                 system db-name storage-dir)
      {:client client
       :conn (d/connect client {:db-name db-name})
       :db-name db-name
       :system system
       :storage-dir storage-dir})
    (catch Exception e
      (log/error e "Failed to start Datomic dev-local" (pr-str cfg))
      {:error e})))

(defn temp-connection!
  "Create a temporary dev-local database (default :mem storage) and return the
  connection. Accepts the same config map as `connect!` and allows overriding
  the generated db name via {:db-name ...}."
  ([cfg] (temp-connection! cfg {}))
  ([cfg {:keys [db-name]}]
   (let [base (or cfg {})
         temp-name (or db-name (str "temp-" (UUID/randomUUID)))
         temp-cfg (-> base
                      (update :storage-dir #(or % :mem))
                      (update :system #(or % "darelwasl"))
                      (assoc :db-name temp-name))]
     (connect! temp-cfg))))

(defn delete-database!
  "Delete the provided database using the client from state map. Returns true on
  success, false on failure/no-op."
  ([state] (delete-database! state (:db-name state)))
  ([{:keys [client]} db-name]
   (if (and client db-name)
     (try
       (d/delete-database client {:db-name db-name})
       (log/infof "Deleted Datomic dev-local db '%s'" db-name)
       true
       (catch Exception e
         (log/warn e (format "Failed to delete Datomic db '%s'" db-name))
         false))
     (do
       (log/warn "No client/db-name provided for delete-database!")
       false))))

(defn status
  "Return {:status :ok} when a connection can produce a db value; otherwise
  {:status :error :message ...}."
  [db-state]
  (let [{:keys [conn error db-name system storage-dir]} (or db-state {})
        base {:db-name db-name
              :system system
              :storage-kind (cond
                              (= :mem storage-dir) "mem"
                              (= ":mem" storage-dir) "mem"
                              (nil? storage-dir) "unknown"
                              :else "dir")}
        err-msg (cond
                  (nil? error) nil
                  (instance? Exception error) (.getMessage ^Exception error)
                  :else (str error))]
    (cond
      error (assoc base :status :error :message err-msg)
      conn (try
             (let [db (d/db conn)]
               (assoc base
                      :status :ok))
             (catch Exception e
               (assoc base :status :error :message (.getMessage e))))
      :else (assoc base :status :error :message "No Datomic connection"))))

(defn transact!
  "Wrapper around datomic transact."
  [conn tx]
  (d/transact conn tx))
