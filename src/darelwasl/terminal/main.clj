(ns darelwasl.terminal.main
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [darelwasl.config :as config]
            [darelwasl.terminal.http :as http]
            [darelwasl.terminal.session :as session]
            [darelwasl.terminal.store :as store]
            [ring.adapter.jetty :as jetty]))

(defonce system-state (atom nil))

(defn start!
  ([] (start! (config/load-config)))
  ([cfg]
   (let [terminal-cfg (:terminal cfg)
         terminal-store (store/load-store (:data-dir terminal-cfg))]
     (session/reconcile-orphaned-sessions! terminal-store terminal-cfg)
     (let [state {:config cfg
                  :terminal/config terminal-cfg
                  :terminal/store terminal-store}
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
