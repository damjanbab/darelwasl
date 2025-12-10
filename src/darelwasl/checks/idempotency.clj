(ns darelwasl.checks.idempotency
  (:require [darelwasl.config :as config]
            [darelwasl.db :as db]
            [darelwasl.importer :as importer]
            [darelwasl.schema :as schema]
            [datomic.client.api :as d]))

(defn- count-type
  [db entity-type]
  (or (ffirst (d/q '[:find (count ?e)
                     :in $ ?type
                     :where [?e :entity/type ?type]]
                   db entity-type))
      0))

(defn- fail! [msg & [details]]
  (println "Idempotency check failed:" msg (or details ""))
  (System/exit 1))

(defn- check-import-idempotency!
  []
  (let [cfg (config/load-config)
        {:keys [conn error] :as state} (schema/temp-db-with-schema! (assoc (:datomic cfg) :storage-dir :mem))]
    (if error
      (fail! "Unable to start temp DB" error)
      (try
        (let [file importer/default-csv-path
              first-run (importer/import-data! {:conn conn :file file})
              counts-1 {:persons (count-type (d/db conn) :entity.type/person)
                        :parcels (count-type (d/db conn) :entity.type/parcel)
                        :ownerships (count-type (d/db conn) :entity.type/ownership)}]
          (when (:error first-run)
            (fail! "First import run failed" (:error first-run)))
          (importer/import-data! {:conn conn :file file})
          (let [counts-2 {:persons (count-type (d/db conn) :entity.type/person)
                          :parcels (count-type (d/db conn) :entity.type/parcel)
                          :ownerships (count-type (d/db conn) :entity.type/ownership)}]
            (if (= counts-1 counts-2)
              (do
                (println "Importer idempotency passed" counts-1)
                (System/exit 0))
              (fail! "Counts changed after re-import" {:first counts-1 :second counts-2}))))
        (catch Exception e
          (fail! "Exception during idempotency check" (.getMessage e)))
        (finally
          (db/delete-database! state))))))

(defn -main [& _]
  (check-import-idempotency!))
