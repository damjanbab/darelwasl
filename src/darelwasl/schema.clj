(ns darelwasl.schema
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.db :as db])
  (:import (java.io PushbackReader)))

(def default-registry-path "registries/schema.edn")

(defn read-registry
  "Read the Datomic schema registry EDN from disk."
  ([] (read-registry default-registry-path))
  ([path]
   (let [file (io/file path)]
     (when-not (.exists file)
       (throw (ex-info (str "Schema registry not found at " (.getPath file))
                       {:path (.getPath file)})))
     (with-open [r (PushbackReader. (io/reader file))]
       (edn/read r)))))

(defn- attribute->tx
  [{:keys [ident type cardinality unique doc] :as attr}]
  (when (or (nil? ident) (nil? type) (nil? cardinality))
    (throw (ex-info "Schema attribute missing required fields" {:attribute attr})))
  (cond-> {:db/ident ident
           :db/valueType type
           :db/cardinality cardinality}
    unique (assoc :db/unique unique)
    doc (assoc :db/doc doc)))

(defn registry->tx-data
  "Translate registry entries into Datomic tx data."
  [registry]
  (->> registry
       (mapcat :attributes)
       (mapv attribute->tx)))

(defn load-schema!
  "Transact schema derived from registry into the provided connection. Returns
  {:status :ok :tx-count n} or {:error e} on failure."
  ([conn] (load-schema! conn (read-registry)))
  ([conn registry]
   (let [tx-data (registry->tx-data registry)]
     (try
       (d/transact conn {:tx-data tx-data})
       {:status :ok
        :tx-count (count tx-data)}
       (catch Exception e
         (log/error e "Failed to load Datomic schema from registry")
         {:error e})))))

(defn temp-db-with-schema!
  "Spin up a temporary Datomic database (defaults to :mem storage), apply the
  registry schema, and return the db state map from `darelwasl.db/temp-connection!`
  enriched with schema metadata. On failure returns {:error e}."
  ([datomic-cfg] (temp-db-with-schema! datomic-cfg default-registry-path))
  ([datomic-cfg registry-path]
   (let [registry (read-registry registry-path)
         state (db/temp-connection! datomic-cfg)]
     (if-let [err (:error state)]
       state
       (let [{:keys [error tx-count]} (load-schema! (:conn state) registry)
             registry-abs (.getAbsolutePath ^java.io.File (io/file registry-path))]
         (if error
           (do
             (db/delete-database! state)
             (assoc state :error error))
           (assoc state
                  :schema/registry registry-abs
                  :schema/tx-count tx-count)))))))

(defn with-temp-db
  "Utility to execute f against a temp DB loaded with schema. Ensures the
  database is deleted afterward. Returns f's result or {:error e}."
  ([datomic-cfg f] (with-temp-db datomic-cfg default-registry-path f))
  ([datomic-cfg registry-path f]
   (let [state (temp-db-with-schema! datomic-cfg registry-path)]
     (if-let [err (:error state)]
       {:error err}
       (try
         (f state)
         (finally
           (db/delete-database! state)))))))
