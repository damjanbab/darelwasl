(ns darelwasl.main
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [darelwasl.auth :as auth]
            [darelwasl.bootstrap :as bootstrap]
            [darelwasl.config :as config]
            [darelwasl.server :as server]
            [darelwasl.site.server :as site-server]
            [darelwasl.telegram :as telegram]
            [darelwasl.workers.outbox :as outbox-worker]))

(defonce system-state (atom nil))

(defn start!
  "Start the application with optional config override."
  ([] (start! (config/load-config)))
  ([cfg]
   (let [db-state (bootstrap/initialize-db! cfg {:context "startup"
                                                 :recreate-msg "Schema load failed due to incompatible existing DB; recreating database"})
         users (auth/load-users!)
         user-index (auth/user-index-by-username users)
         _ (when (:error db-state)
             (log/warn (:error db-state) "Datomic dev-local not ready; health endpoint will report error"))
         base {:config cfg
               :db db-state
               :auth/users users
               :auth/user-index user-index}
         started (-> base
                     (server/start-http)
                     (site-server/start))
         outbox-enabled? (get-in cfg [:outbox :worker-enabled?])
         worker-future (when (and outbox-enabled? (not (:error db-state)))
                         (future
                           (try
                             (outbox-worker/run-loop!
                              {:config cfg
                               :db db-state}
                              {:poll-ms (get-in cfg [:outbox :poll-ms])})
                             (catch InterruptedException _
                               (log/info "Outbox worker stopped"))
                             (catch Exception e
                               (log/error e "Outbox worker crashed")))))
         started (cond-> started
                   worker-future (assoc :outbox/worker worker-future))]
     (telegram/auto-set-webhook! (:telegram cfg))
     (reset! system-state started)
     started)))

(defn stop!
  "Stop the running application."
  []
  (when-let [state @system-state]
    (when-let [worker (:outbox/worker state)]
      (future-cancel worker))
    (-> state
        (site-server/stop)
        (server/stop-http))
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
