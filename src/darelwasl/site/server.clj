(ns darelwasl.site.server
  (:require [clojure.tools.logging :as log]
            [darelwasl.site.http :as site-http]
            [ring.adapter.jetty :as jetty]))

(defn start
  "Start Jetty for the public site process. Uses :config :site for host/port."
  [state]
  (let [{:keys [host port]} (get-in state [:config :site])
        handler (site-http/app state)
        server (jetty/run-jetty handler {:port port
                                         :host host
                                         :join? false})]
    (log/infof "Public site listening on http://%s:%s" host port)
    (assoc state :site/server server)))

(defn stop
  "Stop the public site server if running."
  [state]
  (when-let [server (:site/server state)]
    (try
      (log/info "Stopping public site server")
      (.stop ^org.eclipse.jetty.server.Server server)
      (catch Exception e
        (log/warn e "Error while stopping public site server"))))
  (dissoc state :site/server))
