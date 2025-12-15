(ns darelwasl.site.main
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [darelwasl.bootstrap :as bootstrap]
            [darelwasl.config :as config]
            [darelwasl.site.server :as site-server]))

(defonce site-state (atom nil))

(defn start!
  "Start the public site process. Pass {:start-server? false} for a dry-run."
  ([]
   (start! {}))
  ([{:keys [start-server?] :or {start-server? true}}]
   (let [cfg (config/load-config)
         db-state (bootstrap/initialize-db! cfg {:context "site startup"
                                                 :recreate-msg "Schema load failed; recreating database for site process"})
         base-state {:config cfg
                     :db db-state}]
     (if-not (:conn db-state)
       (do
         (log/warn "Public site not started; Datomic connection unavailable")
         (reset! site-state base-state)
         base-state)
       (let [started (if start-server?
                       (site-server/start base-state)
                       base-state)]
         (reset! site-state started)
         started)))))

(defn stop!
  "Stop the public site process."
  []
  (when-let [state @site-state]
    (reset! site-state (site-server/stop state))))

(defn -main
  [& args]
  (let [dry-run? (some #{"--dry-run"} args)]
    (log/info "Starting public site process")
    (start! {:start-server? (not dry-run?)})
    (when dry-run?
      (log/info "Public site dry-run completed")
      (stop!)
      (System/exit 0))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (log/info "Shutting down public site process")
                                 (stop!))))
    (when-let [server (:site/server @site-state)]
      (.join ^org.eclipse.jetty.server.Server server))))
