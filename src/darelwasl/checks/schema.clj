(ns darelwasl.checks.schema
  (:require [darelwasl.config :as config]
            [darelwasl.db :as db]
            [darelwasl.schema :as schema]))

(defn- datomic-config []
  (-> (config/load-config)
      :datomic
      ;; Force temp storage to avoid persisting schema checks on disk.
      (assoc :storage-dir :mem)))

(defn run-check!
  "Load the Datomic schema registry into a temp dev-local database. Exits with
  non-zero status if the schema cannot be transacted or the connection is
  unhealthy."
  []
  (let [datomic-cfg (datomic-config)
        state (schema/temp-db-with-schema! datomic-cfg)
        result (try
                 (if-let [err (:error state)]
                   {:code 1
                    :lines [(str "Schema load failed: " (or (ex-message err) err))]}
                   (let [{:keys [status message]} (db/status state)]
                     (if (= status :error)
                       {:code 1
                        :lines [(str "Schema load connection check failed: " message)]}
                       {:code 0
                        :lines ["Schema load check passed."
                                (str "Registry: " (:schema/registry state))
                                (format "Transacted %s schema statements into temp DB %s (system %s, storage %s)"
                                        (:schema/tx-count state)
                                        (:db-name state)
                                        (:system state)
                                        (:storage-dir state))]})))
                 (catch Exception e
                   {:code 1
                    :lines [(str "Schema load check crashed: " (ex-message e))]}))]
    (when (map? state)
      (db/delete-database! state))
    (doseq [line (:lines result)]
      (println line))
    (System/exit (:code result))))

(defn -main [& _] (run-check!))
