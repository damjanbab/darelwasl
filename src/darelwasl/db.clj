(ns darelwasl.db
  (:require [clojure.tools.logging :as log]
            [datomic.client.api :as d]))

(defn connect!
  "Start a Datomic dev-local client + connection based on config map with keys
  {:storage-dir :system :db-name}. Creates the database if it does not exist.
  Returns {:client .. :conn ..} on success, {:error ...} on failure."
  [{:keys [storage-dir system db-name] :as cfg}]
  (try
    (let [client (d/client {:server-type :dev-local
                            :storage-dir storage-dir
                            :system system})]
      (d/create-database client {:db-name db-name})
      (log/infof "Connected to Datomic dev-local system '%s' db '%s' (storage %s)"
                 system db-name storage-dir)
      {:client client
       :conn (d/connect client {:db-name db-name})})
    (catch Exception e
      (log/error e "Failed to start Datomic dev-local" (pr-str cfg))
      {:error e})))

(defn status
  "Return {:status :ok} when a connection can produce a db value; otherwise
  {:status :error :message ...}."
  [{:keys [conn error]}]
  (cond
    error {:status :error
           :message (.getMessage ^Exception error)}
    conn (try
           (d/db conn)
           {:status :ok}
           (catch Exception e
             {:status :error
              :message (.getMessage e)}))
    :else {:status :error
           :message "No Datomic connection"}))
