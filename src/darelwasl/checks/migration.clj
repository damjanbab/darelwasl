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
                       (d/transact conn {:tx-data tx-data}))
                     (schema/backfill-entity-types! conn)
                     (catch Exception e
                       {:error e}))]
        (db/delete-database! state)
        (cond
          (:error result)
          (do
            (println "Migration backfill check failed:" (.getMessage ^Exception (:error result)))
            (System/exit 1))

          (not= (:added result) expected)
          (do
            (println "Migration backfill check failed: expected to add" expected "types but added" (:added result))
            (System/exit 1))

          :else
          (do
            (log/infof "Backfill restored %s entity types" (:added result))
            (println "Migration backfill check passed.")
            (System/exit 0)))))))

(defn -main [& _] (run-check!))
