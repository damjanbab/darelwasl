(ns darelwasl.fixtures
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.config :as config]
            [darelwasl.db :as db]
            [darelwasl.schema :as schema])
  (:import (java.io PushbackReader)))

(def default-users-path "fixtures/users.edn")
(def default-tasks-path "fixtures/tasks.edn")

(defn- read-fixture
  [path]
  (let [file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info (str "Fixture file not found: " (.getPath file))
                      {:path (.getPath file)})))
    (with-open [r (PushbackReader. (io/reader file))]
      (edn/read r))))

(defn load-fixtures
  "Read fixture data from disk. Returns {:users [...] :tasks [...]}. Paths
  default to fixtures/users.edn and fixtures/tasks.edn."
  ([] (load-fixtures default-users-path default-tasks-path))
  ([users-path tasks-path]
   {:users (read-fixture users-path)
    :tasks (read-fixture tasks-path)}))

(defn- task->tx
  "Prepare a task fixture for Datomic transact by converting the assignee UUID
  into a lookup ref against :user/id."
  [task]
  (update task :task/assignee (fn [user-id] [:user/id user-id])))

(defn seed-conn!
  "Transact fixtures into an existing Datomic connection. Returns {:status :ok
  :users n :tasks n} or {:error e} on failure."
  ([conn] (seed-conn! conn (load-fixtures)))
  ([conn {:keys [users tasks]}]
   (try
     (when (seq users)
       (d/transact conn {:tx-data users}))
     (when (seq tasks)
       (d/transact conn {:tx-data (map task->tx tasks)}))
     {:status :ok
      :users (count users)
      :tasks (count tasks)}
     (catch Exception e
       (log/error e "Failed to seed fixtures into Datomic")
       {:error e}))))

(defn seed-dev!
  "Connect to the configured dev-local Datomic DB, load schema, and seed
  fixtures. Returns the DB state merged with {:status :ok :users n :tasks n} or
  {:error e}."
  ([] (seed-dev! (config/load-config)))
  ([cfg]
   (let [{:keys [datomic]} cfg
         state (db/connect! datomic)]
     (if-let [err (:error state)]
       state
       (let [{schema-error :error} (schema/load-schema! (:conn state))]
         (if schema-error
           (assoc state :error schema-error)
           (merge state (seed-conn! (:conn state)))))))))

(defn temp-db-with-fixtures!
  "Create a temporary Datomic DB (defaults to :mem storage), load schema, and
  seed fixtures. Returns the state map from `schema/temp-db-with-schema!`
  merged with {:status :ok :users n :tasks n} or {:error e}. On fixture load
  failure, the temp DB is deleted."
  ([] (temp-db-with-fixtures! (:datomic (config/load-config))))
  ([datomic-cfg]
   (let [temp-cfg (assoc datomic-cfg :storage-dir :mem)
         state (schema/temp-db-with-schema! temp-cfg)]
     (if-let [err (:error state)]
       state
       (let [{:keys [error] :as seeded} (seed-conn! (:conn state))]
         (if error
           (do
             (db/delete-database! state)
             (assoc state :error error))
           (merge state seeded)))))))

(defn with-temp-fixtures
  "Helper to work with a temp DB preloaded with schema + fixtures. Executes f
  with the state map and ensures deletion afterward. Returns f's result or
  {:error e} if setup fails."
  ([f] (with-temp-fixtures (:datomic (config/load-config)) f))
  ([datomic-cfg f]
   (let [state (temp-db-with-fixtures! datomic-cfg)]
     (if-let [err (:error state)]
       {:error err}
       (try
         (f state)
         (finally
           (db/delete-database! state)))))))

(defn -main
  "Seed fixtures into the dev DB (default) or a temp DB when --temp is passed.
  Example:
    clojure -M:seed
    clojure -M:seed --temp"
  [& args]
  (let [mode (if (some #{"--temp"} args) :temp :dev)
        cfg (config/load-config)]
    (cond
      (= mode :temp)
      (let [state (temp-db-with-fixtures! (:datomic cfg))]
        (if-let [err (:error state)]
          (do
            (println "Fixture seed failed (temp db):" (.getMessage ^Exception err))
            (System/exit 1))
          (do
            (println "Seeded temp Datomic DB" (:db-name state) "with fixtures")
            (println "Storage" (:storage-dir state) "system" (:system state))
            (System/exit 0))))

      (= mode :dev)
      (let [state (seed-dev! cfg)]
        (if-let [err (:error state)]
          (do
            (println "Fixture seed failed (dev db):" (.getMessage ^Exception err))
            (System/exit 1))
          (do
            (println "Seeded dev Datomic DB" (:db-name state) "with fixtures")
            (System/exit 0))))

      :else
      (do
        (println "Usage: clojure -M:seed [--temp]")
        (System/exit 1)))))
