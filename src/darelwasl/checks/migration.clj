(ns darelwasl.checks.migration
  (:require [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.config :as config]
            [darelwasl.db :as db]
            [darelwasl.fixtures :as fixtures]
            [darelwasl.schema :as schema]))

(defn run-check!
  "Seed a temp DB with fixtures, strip :entity/type, run the backfill, and fail
  if the backfill does not restore types."
  []
  (let [datomic-cfg (-> (config/load-config) :datomic (assoc :storage-dir :mem))
        state (fixtures/temp-db-with-fixtures! datomic-cfg)]
    (if-let [err (:error state)]
      (do
        (println "Migration check failed during setup:" (.getMessage ^Exception err))
        (System/exit 1))
      (let [conn (:conn state)
            db (d/db conn)
            pairs (d/q '[:find ?e ?t
                         :where [?e :entity/type ?t]]
                       db)
            tx-data (map (fn [[e t]] [:db/retract e :entity/type t]) pairs)
            expected (count pairs)
            result (try
                     (when (seq tx-data)
                       (db/transact! conn {:tx-data tx-data}))
                     (schema/backfill-entity-types! conn)
                     (catch Exception e
                       {:error e}))
            ws-result (try
                        (let [db-after (d/db conn)
                              task-ws (d/q '[:find ?e ?ws
                                            :where [?e :task/id _]
                                                   [?e :fact/workspace ?ws]]
                                          db-after)
                              tag-ws (d/q '[:find ?e ?ws
                                           :where [?e :tag/id _]
                                                  [?e :tag/workspace ?ws]]
                                         db-after)
                              note-ws (d/q '[:find ?e ?ws
                                            :where [?e :note/id _]
                                                   [?e :note/workspace ?ws]]
                                          db-after)
                              file-ws (d/q '[:find ?e ?ws
                                            :where [?e :file/id _]
                                                   [?e :file/workspace ?ws]]
                                          db-after)
                              ws-tx (concat (map (fn [[e ws]] [:db/retract e :fact/workspace ws]) task-ws)
                                            (map (fn [[e ws]] [:db/retract e :tag/workspace ws]) tag-ws)
                                            (map (fn [[e ws]] [:db/retract e :note/workspace ws]) note-ws)
                                            (map (fn [[e ws]] [:db/retract e :file/workspace ws]) file-ws))
                              ws-expected (+ (count task-ws)
                                             (count tag-ws)
                                             (count note-ws)
                                             (count file-ws))]
                          (when (seq ws-tx)
                            (db/transact! conn {:tx-data ws-tx}))
                          (let [ws-backfill (schema/backfill-workspaces! conn)]
                            (assoc ws-backfill :expected ws-expected)))
                        (catch Exception e
                          {:error e}))]
        (db/delete-database! state)
        (cond
          (:error result)
          (do
            (println "Migration backfill check failed:" (.getMessage ^Exception (:error result)))
            (System/exit 1))

          (:error ws-result)
          (do
            (println "Workspace backfill check failed:" (.getMessage ^Exception (:error ws-result)))
            (System/exit 1))

          (not= (:added result) expected)
          (do
            (println "Migration backfill check failed: expected to add" expected "types but added" (:added result))
            (System/exit 1))

          (not= (:added ws-result) (:expected ws-result))
          (do
            (println "Workspace backfill check failed: expected to add"
                     (:expected ws-result)
                     "workspace attributes but added"
                     (:added ws-result))
            (System/exit 1))

          :else
          (do
            (log/infof "Backfill restored %s entity types" (:added result))
            (log/infof "Backfill restored %s workspace attributes" (:added ws-result))
            (println "Migration backfill check passed.")
            (System/exit 0)))))))

(defn -main [& _] (run-check!))
