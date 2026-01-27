(ns darelwasl.http.routes.system
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.db :as db]
            [darelwasl.http.common :as common]))

(defn- now-ms [] (System/currentTimeMillis))

(defn- normalize-host
  [host]
  (cond
    (str/blank? (str host)) "127.0.0.1"
    (= "0.0.0.0" host) "127.0.0.1"
    :else host))

(defn- service-url
  [host port]
  (when (and host port)
    (str "http://" (normalize-host host) ":" port)))

(defn- join-url
  [base path]
  (str (str/replace (or base "") #"/+$" "") path))

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

(defn- site-restart-handler
  [state]
  (fn [_request]
    (let [site-enabled? (true? (get-in state [:config :site :enabled?]))]
      (cond
        (not site-enabled?)
        (common/error-response 400 "Site disabled.")

        (nil? (:site/restart! state))
        (common/error-response 500 "Site restart unavailable.")

        :else
        (try
          ((:site/restart! state))
          {:status 202
           :body {:status "restarting"}}
          (catch Exception e
            (log/warn e "Failed to restart site")
            (common/error-response 500 "Unable to restart site.")))))))

(defn- http-health
  [url path]
  (if (str/blank? (str url))
    {:status "missing"
     :message "Not configured"}
    (let [start (System/nanoTime)]
      (try
        (let [resp (http/request {:method :get
                                  :url (join-url url path)
                                  :throw-exceptions false
                                  :socket-timeout 2000
                                  :conn-timeout 2000
                                  :as :text})
              status (:status resp)
              ok? (<= 200 status 299)
              dur-ms (/ (double (- (System/nanoTime) start)) 1e6)]
          {:status (if ok? "ok" "error")
           :http-status status
           :latency-ms (long dur-ms)
           :message (when-not ok?
                      (str "HTTP " status))})
        (catch Exception e
          {:status "error"
           :message (.getMessage e)})))))

(defn- app-health
  [state]
  (let [db-status (db/status (:db state))
        status (if (= :ok (:status db-status)) "ok" "error")]
    {:status status
     :datastore db-status}))

(defn- site-health
  [site-url]
  (http-health site-url "/"))

(defn- services-handler
  [state]
  (fn [_request]
    (let [cfg (:config state)
          site-enabled? (true? (get-in cfg [:site :enabled?]))
          app-url (service-url (get-in cfg [:http :host]) (get-in cfg [:http :port]))
          site-url (when site-enabled?
                     (service-url (get-in cfg [:site :host]) (get-in cfg [:site :port])))
          checked-at (now-ms)
          services (cond-> [{:id "app"
                             :label "App API"
                             :url app-url
                             :restartable? (boolean (:app/restart! state))
                             :health (assoc (app-health state) :checked-at checked-at)}]
                     site-enabled?
                     (conj {:id "site"
                            :label "Public site"
                            :url site-url
                            :restartable? (boolean (:site/restart! state))
                            :health (assoc (site-health site-url) :checked-at checked-at)}))]
      {:status 200
       :body {:services services}})))

(defn- restart-service-handler
  [state]
  (fn [request]
    (let [service-id (get-in request [:path-params :id])
          cfg (:config state)]
      (case service-id
        "app" ((restart-handler state) request)
        "site" (if (true? (get-in cfg [:site :enabled?]))
                 ((site-restart-handler state) request)
                 (common/error-response 400 "Site disabled."))
        (common/error-response 404 "Unknown service")))))

(defn routes
  [state]
  [["/system"
    {:middleware [common/require-session
                  (common/require-roles #{:role/admin})]}
    ["/restart" {:post (restart-handler state)}]
    ["/site/restart" {:post (site-restart-handler state)}]
    ["/services" {:get (services-handler state)}]
    ["/services/:id/restart" {:post (restart-service-handler state)}]]])
