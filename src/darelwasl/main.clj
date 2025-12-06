(ns darelwasl.main
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [darelwasl.auth :as auth]
            [darelwasl.config :as config]
            [darelwasl.db :as db]
            [darelwasl.server :as server]))

(defonce system-state (atom nil))

(defn start!
  "Start the application with optional config override."
  ([] (start! (config/load-config)))
  ([cfg]
   (let [db-state (db/connect! (:datomic cfg))
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
