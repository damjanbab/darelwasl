(ns darelwasl.main
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.auth :as auth]
            [darelwasl.config :as config]
            [darelwasl.db :as db]
            [darelwasl.fixtures :as fixtures]
            [datomic.client.api :as d]
            [darelwasl.schema :as schema]
            [darelwasl.tasks :as tasks]
            [darelwasl.server :as server]))

(defonce system-state (atom nil))

(defn- prepare-db!
  "Load schema and seed fixtures when the database is empty. Returns the db
  state map with any schema/fixture metadata or errors attached."
  [{:keys [conn] :as db-state}]
  (if-not conn
    db-state
    (let [{:keys [error tx-count]} (schema/load-schema! conn)]
      (if error
        (do
          (log/error error "Schema load failed during startup")
          (assoc db-state :error error))
        (let [{bf-error :error added :added} (schema/backfill-entity-types! conn)
              db (d/db conn)
              has-users? (seq (d/q '[:find ?e :where [?e :user/id _]] db))
              has-tasks? (seq (d/q '[:find ?e :where [?e :task/id _]] db))
              has-tags? (seq (d/q '[:find ?e :where [?e :tag/id _]] db))
              prepared (cond
                         bf-error (assoc db-state :error bf-error)
                         (and has-users? has-tasks? has-tags?)
                         (do
                           (when (pos? (or added 0))
                             (log/infof "Backfilled :entity/type on %s existing entities" added))
                           (log/info "Schema loaded; skipping fixture seed (data already present)")
                           (assoc db-state :schema/tx-count tx-count))
                         :else
                         (let [{seed-error :error users :users tags :tags tasks :tasks} (fixtures/seed-conn! conn)]
                           (if seed-error
                             (do
                               (log/error seed-error "Fixture seed failed during startup")
                               (assoc db-state :error seed-error))
                             (do
                               (log/infof "Schema loaded (%s attrs). Seeded fixtures (users=%s tags=%s tasks=%s)." tx-count users tags tasks)
                               (assoc db-state
                                      :schema/tx-count tx-count
                                      :fixtures/seed {:users users
                                                      :tags tags
                                                      :tasks tasks})))))]
          (tasks/migrate-tags! conn)
          prepared)))))

(defn- unsupported-alter-schema?
  [e]
  (let [data (ex-data e)
        msg (when e (.getMessage ^Exception e))]
    (or (= (:db/error data) :db.error/unsupported-alter-schema)
        (and msg (str/includes? msg "unsupported-alter-schema")))))

(defn- initialize-db!
  "Connect to Datomic, load schema/fixtures, and attempt a one-time recovery if
  schema load fails due to incompatible existing schema (unsupported alter)."
  [datomic-cfg]
  (loop [attempt 1
         state (db/connect! datomic-cfg)]
    (cond
      (:error state) state
      :else
      (let [prepared (prepare-db! state)]
        (if-let [err (:error prepared)]
          (if (and (= attempt 1) (unsupported-alter-schema? err))
            (do
              (log/warn err "Schema load failed due to incompatible existing DB; recreating database")
              (db/delete-database! state)
              (recur 2 (db/connect! datomic-cfg)))
            prepared)
          prepared)))))

(defn start!
  "Start the application with optional config override."
  ([] (start! (config/load-config)))
  ([cfg]
   (let [db-state (initialize-db! (:datomic cfg))
         users (auth/load-users!)
         user-index (auth/user-index-by-username users)
         _ (when (:error db-state)
             (log/warn (:error db-state) "Datomic dev-local not ready; health endpoint will report error"))
         started (server/start-http {:config cfg
                                     :db db-state
                                     :auth/users users
                                     :auth/user-index user-index})]
     (reset! system-state started)
     started)))

(defn stop!
  "Stop the running application."
  []
  (when-let [state @system-state]
    (server/stop-http state)
    (reset! system-state nil)))

(defn -main
  "Entry point for `clojure -M:dev`."
  [& _args]
  (log/info "Starting darelwasl service")
  (start!)
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn []
                               (log/info "Shutting down darelwasl service")
                               (stop!))))
  (when-let [server (get-in @system-state [:http/server])]
    (.join ^org.eclipse.jetty.server.Server server)))
