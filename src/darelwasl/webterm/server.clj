(ns darelwasl.webterm.server
  (:require [darelwasl.webterm.config :as cfg]
            [darelwasl.webterm.http :as http]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(defonce ^:private server* (atom nil))

(defn start!
  []
  (let [{:keys [listen-host listen-port]} (cfg/config)
        handler (http/app)]
    (when @server*
      (throw (ex-info "server already running" {})))
    (reset! server* (jetty/run-jetty handler {:host listen-host
                                              :port listen-port
                                              :join? false}))
    (println (format "webterm-ui listening on http://%s:%s" listen-host listen-port))
    nil))

(defn stop!
  []
  (when-let [s @server*]
    (.stop ^org.eclipse.jetty.server.Server s)
    (reset! server* nil))
  nil)

(defn -main
  [& args]
  (cond
    (some #{"--check"} args) (do (require 'darelwasl.webterm.http) (println :ok) (System/exit 0))
    :else (do (start!)
              ;; Keep process alive.
              (loop [] (Thread/sleep 60000) (recur)))))

