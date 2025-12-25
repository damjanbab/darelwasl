(ns darelwasl.bootstrap
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.db :as db]
            [darelwasl.fixtures :as fixtures]
            [darelwasl.schema :as schema]
            [darelwasl.tasks :as tasks]
            [datomic.client.api :as d]))

(defn unsupported-alter-schema?
  [e]
  (let [data (ex-data e)
        msg (when e (.getMessage ^Exception e))]
    (or (= (:db/error data) :db.error/unsupported-alter-schema)
        (and msg (str/includes? msg "unsupported-alter-schema")))))

(defn prepare-db!
  "Load schema, backfill, and seed fixtures when appropriate. Accepts
  optional {:context \"...\"} to tailor log messages."
  ([db-state cfg]
   (prepare-db! db-state cfg {}))
  ([{:keys [conn] :as db-state} {:keys [fixtures] :as _cfg} {:keys [context] :or {context "startup"}}]
   (if-not conn
     db-state
     (let [{:keys [error tx-count]} (schema/load-schema! conn)
           auto-seed? (get fixtures :auto-seed? true)]
       (if error
         (do
           (log/error error (str "Schema load failed during " context))
           (assoc db-state :error error))
         (let [{bf-error :error added :added} (schema/backfill-entity-types! conn)
               {ref-error :error ref-added :added} (schema/backfill-entity-refs! conn)
               db (d/db conn)
               seeded? (fixtures/seeded? db)
               has-users? (seq (d/q '[:find ?e :where [?e :user/id _]] db))
               has-tasks? (seq (d/q '[:find ?e :where [?e :task/id _]] db))
               has-tags? (seq (d/q '[:find ?e :where [?e :tag/id _]] db))
               prepared (cond
                          bf-error (assoc db-state :error bf-error)
                          ref-error (assoc db-state :error ref-error)
                          seeded?
                          (do
                            (when (pos? (or added 0))
                              (log/infof "Backfilled :entity/type on %s existing entities" added))
                            (when (pos? (or ref-added 0))
                              (log/infof "Backfilled :entity/ref on %s existing entities" ref-added))
                            (log/info "Schema loaded; seed marker present, skipping fixture seed")
                            (assoc db-state :schema/tx-count tx-count))
                          (not auto-seed?)
                          (do
                            (when (pos? (or added 0))
                              (log/infof "Backfilled :entity/type on %s existing entities" added))
                            (when (pos? (or ref-added 0))
                              (log/infof "Backfilled :entity/ref on %s existing entities" ref-added))
                            (log/info "Schema loaded; fixture seeding disabled via ALLOW_FIXTURE_SEED")
                            (assoc db-state :schema/tx-count tx-count))
                          (and has-users? has-tasks? has-tags?)
                          (do
                            (when (pos? (or added 0))
                              (log/infof "Backfilled :entity/type on %s existing entities" added))
                            (when (pos? (or ref-added 0))
                              (log/infof "Backfilled :entity/ref on %s existing entities" ref-added))
                            (log/info "Schema loaded; skipping fixture seed (data already present)")
                            (assoc db-state :schema/tx-count tx-count))
                          :else
                           (let [{seed-error :error users :users tags :tags tasks :tasks} (fixtures/seed-conn! conn)]
                             (if seed-error
                               (do
                                 (log/error seed-error (str "Fixture seed failed during " context))
                                 (assoc db-state :error seed-error))
                               (do
                                 (log/infof "Schema loaded (%s attrs). Seeded fixtures (users=%s tags=%s tasks=%s)." tx-count users tags tasks)
                                 (assoc db-state
                                        :schema/tx-count tx-count
                                        :fixtures/seed {:users users
                                                        :tags tags
                                                        :tasks tasks})))))]
           (tasks/migrate-tags! conn)
           prepared))))))

(defn initialize-db!
  "Connect to Datomic, load schema/fixtures, and retry once if incompatible schema is detected.
  Options:
  - :context (string) used in log messages.
  - :recreate-msg (string) overrides the warning when recreating the DB."
  ([cfg]
   (initialize-db! cfg {}))
  ([cfg {:keys [context recreate-msg]}]
   (let [ctx (or context "startup")
         recreate-msg (or recreate-msg (str "Schema load failed; recreating database during " ctx))]
     (loop [attempt 1
            state (db/connect! (:datomic cfg))]
       (cond
         (:error state) state
         :else
         (let [prepared (prepare-db! state cfg {:context ctx})]
           (if-let [err (:error prepared)]
             (if (and (= attempt 1) (unsupported-alter-schema? err))
               (do
                 (log/warn err recreate-msg)
                 (db/delete-database! state)
                 (recur 2 (db/connect! (:datomic cfg))))
               prepared)
             prepared)))))))
