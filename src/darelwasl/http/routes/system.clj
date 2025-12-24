(ns darelwasl.http.routes.system
  (:require [clojure.tools.logging :as log]
            [darelwasl.http.common :as common]))

(defn- restart-handler
  [state]
  (fn [_request]
    (if-let [restart! (:app/restart! state)]
      (try
        (restart!)
        {:status 202
         :body {:status "restarting"}}
        (catch Exception e
          (log/warn e "Failed to schedule restart")
          (common/error-response 500 "Unable to restart server.")))
      (common/error-response 500 "Restart unavailable."))))

(defn routes
  [state]
  [["/system"
    {:middleware [common/require-session
                  (common/require-roles #{:role/admin})]}
    ["/restart" {:post (restart-handler state)}]]])
