(ns darelwasl.site.main
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.config :as config]
            [darelwasl.db :as db]
            [darelwasl.fixtures :as fixtures]
            [darelwasl.schema :as schema]
            [darelwasl.site.server :as site-server]
            [darelwasl.tasks :as tasks]
            [datomic.client.api :as d]))

(defonce site-state (atom nil))

(defn- unsupported-alter-schema?
  [e]
  (let [data (ex-data e)
        msg (when e (.getMessage ^Exception e))]
    (or (= (:db/error data) :db.error/unsupported-alter-schema)
        (and msg (str/includes? msg "unsupported-alter-schema")))))

(defn- prepare-db!
  "Load schema, backfill, and seed fixtures when appropriate."
  [{:keys [conn] :as db-state} {:keys [fixtures] :as _cfg}]
  (if-not conn
    db-state
    (let [{:keys [error tx-count]} (schema/load-schema! conn)
          auto-seed? (get fixtures :auto-seed? true)]
      (if error
        (do
          (log/error error "Schema load failed during site startup")
          (assoc db-state :error error))
        (let [{bf-error :error added :added} (schema/backfill-entity-types! conn)
              db (d/db conn)
              seeded? (fixtures/seeded? db)
              has-users? (seq (d/q '[:find ?e :where [?e :user/id _]] db))
              has-tasks? (seq (d/q '[:find ?e :where [?e :task/id _]] db))
              has-tags? (seq (d/q '[:find ?e :where [?e :tag/id _]] db))
              prepared (cond
                         bf-error (assoc db-state :error bf-error)
                         seeded?
                         (do
                           (when (pos? (or added 0))
                             (log/infof "Backfilled :entity/type on %s existing entities" added))
                           (log/info "Schema loaded; seed marker present, skipping fixture seed")
                           (assoc db-state :schema/tx-count tx-count))
                         (not auto-seed?)
                         (do
                           (when (pos? (or added 0))
                             (log/infof "Backfilled :entity/type on %s existing entities" added))
                           (log/info "Schema loaded; fixture seeding disabled via ALLOW_FIXTURE_SEED")
                           (assoc db-state :schema/tx-count tx-count))
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
                               (log/error seed-error "Fixture seed failed during site startup")
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

(defn- initialize-db!
  "Connect to Datomic, load schema/fixtures, and retry once if incompatible schema is detected."
  [cfg]
  (loop [attempt 1
         state (db/connect! (:datomic cfg))]
    (cond
      (:error state) state
      :else
      (let [prepared (prepare-db! state cfg)]
        (if-let [err (:error prepared)]
          (if (and (= attempt 1) (unsupported-alter-schema? err))
            (do
              (log/warn err "Schema load failed; recreating database for site process")
              (db/delete-database! state)
              (recur 2 (db/connect! (:datomic cfg))))
            prepared)
          prepared)))))

(defn start!
  "Start the public site process. Pass {:start-server? false} for a dry-run."
  ([]
   (start! {}))
  ([{:keys [start-server?] :or {start-server? true}}]
   (let [cfg (config/load-config)
         db-state (initialize-db! cfg)
         base-state {:config cfg
                     :db db-state}]
     (if-not (:conn db-state)
       (do
         (log/warn "Public site not started; Datomic connection unavailable")
         (reset! site-state base-state)
         base-state)
       (let [started (if start-server?
                       (site-server/start base-state)
                       base-state)]
         (reset! site-state started)
         started)))))

(defn stop!
  "Stop the public site process."
  []
  (when-let [state @site-state]
    (reset! site-state (site-server/stop state))))

(defn -main
  [& args]
  (let [dry-run? (some #{"--dry-run"} args)]
    (log/info "Starting public site process")
    (start! {:start-server? (not dry-run?)})
    (when dry-run?
      (log/info "Public site dry-run completed")
      (stop!)
      (System/exit 0))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (log/info "Shutting down public site process")
                                 (stop!))))
    (when-let [server (:site/server @site-state)]
      (.join ^org.eclipse.jetty.server.Server server))))
