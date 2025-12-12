(ns darelwasl.fixtures
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.config :as config]
            [darelwasl.db :as db]
            [darelwasl.schema :as schema])
  (:import (java.io PushbackReader)
           (java.util Date)))

(def default-users-path "fixtures/users.edn")
(def default-tags-path "fixtures/tags.edn")
(def default-tasks-path "fixtures/tasks.edn")
(def default-content-path "fixtures/content.edn")
(def seed-marker-id :darelwasl/system)

(defn- read-fixture
  [path]
  (let [file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info (str "Fixture file not found: " (.getPath file))
                      {:path (.getPath file)})))
    (with-open [r (PushbackReader. (io/reader file))]
      (edn/read r))))

(defn load-fixtures
  "Read fixture data from disk. Returns {:users [...] :tags [...] :tasks [...] :content {...}}.
  Content includes tags/pages/blocks plus optional v2 entities (businesses, contacts, licenses, comparison rows, journey phases, activation steps, personas, support entries, hero stats/flows, faqs, values, team members).
  Paths default to fixtures/users.edn, fixtures/tags.edn, fixtures/tasks.edn, and fixtures/content.edn."
  ([] (load-fixtures default-users-path default-tags-path default-tasks-path default-content-path))
  ([users-path tags-path tasks-path content-path]
   {:users (read-fixture users-path)
    :tags (read-fixture tags-path)
    :tasks (read-fixture tasks-path)
    :content (read-fixture content-path)}))

(defn- task->tx
  "Prepare a task fixture for Datomic transact by converting the assignee UUID
  into a lookup ref against :user/id. Tags are already provided as lookup refs."
  [task]
  (update task :task/assignee (fn [user-id] [:user/id user-id])))

(defn seeded?
  "Return true when the fixture seed marker is present."
  [db]
  (boolean (seq (d/q '[:find ?e
                       :in $ ?id
                       :where [?e :system/id ?id]]
                     db seed-marker-id))))

(defn- seed-marker-tx
  "Seed marker entity; upserts on :system/id so reruns are idempotent."
  []
  {:system/id seed-marker-id
   :system/seeded-at (Date.)
   :entity/type :entity.type/system})

(defn seed-conn!
  "Transact fixtures into an existing Datomic connection. Returns {:status :ok
  :users n :tags n :tasks n :content-tags n :content-pages n :content-blocks n ...} or {:error e} on failure."
  ([conn] (seed-conn! conn (load-fixtures) {}))
  ([conn fixtures] (seed-conn! conn fixtures {}))
  ([conn {:keys [users tags tasks content]} {:keys [add-marker?] :or {add-marker? true}}]
   (let [content (or content {})
         content-tags (:tags content)
         content-pages (:pages content)
         content-blocks (:blocks content)
         businesses (:businesses content)
         contacts (:contacts content)
         licenses (:licenses content)
         comparison-rows (:comparison-rows content)
         journey-phases (:journey-phases content)
         activation-steps (:activation-steps content)
         personas (:personas content)
         support-entries (:support-entries content)
         hero-stats (:hero-stats content)
         hero-flows (:hero-flows content)
         faqs (:faqs content)
         values (:values content)
         team-members (:team-members content)
         page-blocks-tx (->> (or content-pages [])
                             (mapcat (fn [page]
                                       (let [page-id (:content.page/id page)
                                             block-refs (:content.page/blocks page)]
                                         (when (seq block-refs)
                                           (map (fn [b] [:db/add [:content.page/id page-id] :content.page/blocks b])
                                                block-refs)))))
                             (remove nil?)
                             vec)]
     (try
       (when (seq users)
         (d/transact conn {:tx-data users}))
       (when (seq tags)
         (d/transact conn {:tx-data tags}))
       (when (seq tasks)
         (d/transact conn {:tx-data (map task->tx tasks)}))
       (when (seq content-tags) (d/transact conn {:tx-data content-tags}))
       (when (seq contacts) (d/transact conn {:tx-data contacts}))
       (when (seq hero-stats) (d/transact conn {:tx-data hero-stats}))
       (when (seq hero-flows) (d/transact conn {:tx-data hero-flows}))
       (when (seq values) (d/transact conn {:tx-data values}))
       (when (seq team-members) (d/transact conn {:tx-data team-members}))
       (when (seq licenses) (d/transact conn {:tx-data licenses}))
       (when (seq comparison-rows) (d/transact conn {:tx-data comparison-rows}))
       (when (seq journey-phases) (d/transact conn {:tx-data journey-phases}))
       (when (seq activation-steps) (d/transact conn {:tx-data activation-steps}))
       (when (seq personas) (d/transact conn {:tx-data personas}))
       (when (seq support-entries) (d/transact conn {:tx-data support-entries}))
       (when (seq faqs) (d/transact conn {:tx-data faqs}))
       (when (seq businesses) (d/transact conn {:tx-data businesses}))
       (when (seq content-pages)
         (d/transact conn {:tx-data (map #(dissoc % :content.page/blocks) content-pages)}))
       (when (seq content-blocks) (d/transact conn {:tx-data content-blocks}))
       (when (seq page-blocks-tx) (d/transact conn {:tx-data page-blocks-tx}))
       (when add-marker?
         (d/transact conn {:tx-data [(seed-marker-tx)]}))
       {:status :ok
        :users (count users)
        :tags (count tags)
        :tasks (count tasks)
        :content-tags (count content-tags)
        :content-pages (count content-pages)
        :content-blocks (count content-blocks)
        :businesses (count businesses)
        :contacts (count contacts)
        :licenses (count licenses)
        :comparison-rows (count comparison-rows)
        :journey-phases (count journey-phases)
        :activation-steps (count activation-steps)
        :personas (count personas)
        :support-entries (count support-entries)
        :hero-stats (count hero-stats)
        :hero-flows (count hero-flows)
        :faqs (count faqs)
        :values (count values)
        :team-members (count team-members)}
       (catch Exception e
         (log/error e "Failed to seed fixtures into Datomic")
         {:error e})))))

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
