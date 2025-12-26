(ns darelwasl.terminal.main
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.config :as config]
            [darelwasl.db :as db]
            [darelwasl.terminal.http :as http]
            [darelwasl.terminal.session :as session]
            [darelwasl.terminal.store :as store]
            [ring.adapter.jetty :as jetty]))

(defonce system-state (atom nil))

(defn- nested-terminal?
  []
  (let [session-id (System/getenv "TERMINAL_SESSION_ID")
        allow (System/getenv "TERMINAL_ALLOW_NESTED")]
    (and (not (str/blank? (str session-id)))
         (not= "1" (str allow)))))

(defn- ensure-not-nested!
  []
  (when (nested-terminal?)
    (throw (ex-info "Nested terminal service is not allowed"
                    {:status 400
                     :message "Nested terminal service is not allowed"}))))

(defn start!
  ([] (start! (config/load-config)))
  ([cfg]
   (ensure-not-nested!)
   (let [terminal-cfg (:terminal cfg)
         db-state (db/connect! (:datomic cfg))
         terminal-store (store/load-store (:data-dir terminal-cfg))
         restart-fn (fn []
                      (future
                        (log/info "Restart requested; stopping terminal service")
                        (Thread/sleep 200)
                        (stop!)
                        (log/info "Restarting terminal service")
                        (start! cfg)))]
     (when (:error db-state)
       (log/warn (:error db-state) "Main DB not ready; terminal commands will fail until it recovers"))
     (session/reconcile-orphaned-sessions! terminal-store terminal-cfg)
     (session/rebuild-port-reservations! terminal-store terminal-cfg)
     (let [state {:config cfg
                  :db db-state
                  :terminal/config terminal-cfg
                  :terminal/store terminal-store
                  :terminal/restart! restart-fn}
           handler (http/app state)
           server (jetty/run-jetty handler {:port (:port terminal-cfg)
                                            :host (:host terminal-cfg)
                                            :join? false})]
       (log/infof "Terminal service listening on http://%s:%s"
                  (:host terminal-cfg) (:port terminal-cfg))
       (reset! system-state (assoc state :terminal/server server))
       @system-state))))

(defn stop!
  []
  (when-let [server (:terminal/server @system-state)]
    (try
      (.stop ^org.eclipse.jetty.server.Server server)
      (catch Exception e
        (log/warn e "Failed to stop terminal service"))))
  (reset! system-state nil))

(defn -main
  [& _args]
  (log/info "Starting terminal service")
  (start!)
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn []
                               (log/info "Stopping terminal service")
                               (stop!))))
  (when-let [server (:terminal/server @system-state)]
    (.join ^org.eclipse.jetty.server.Server server)))
