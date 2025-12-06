(ns darelwasl.server
  (:require [clojure.tools.logging :as log]
            [darelwasl.http :as http]
            [ring.adapter.jetty :as jetty]))

(defn start-http
  "Start Jetty server with provided state (expects config at [:config :http]).
  Returns updated state with :http/server."
  [state]
  (let [{:keys [port host]} (get-in state [:config :http])
        handler (http/app state)
        server (jetty/run-jetty handler {:port port
                                         :host host
                                         :join? false})]
    (log/infof "HTTP server listening on http://%s:%s" host port)
    (assoc state :http/server server)))

(defn stop-http
  "Stop Jetty server if running."
  [state]
  (when-let [server (:http/server state)]
    (try
      (log/info "Stopping HTTP server")
      (.stop ^org.eclipse.jetty.server.Server server)
      (catch Exception e
        (log/warn e "Error while stopping server"))))
  (dissoc state :http/server))
