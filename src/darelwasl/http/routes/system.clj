(ns darelwasl.http.routes.system
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.client.api :as d]
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

(defn- terminal-admin-authorized?
  [state request]
  (let [token (get-in state [:config :terminal :admin-token])
        provided (get-in request [:headers "x-terminal-admin-token"])]
    (and (not (str/blank? (str token)))
         (= token provided))))

(defn- datastore-snapshot
  [state]
  (let [{:keys [conn error]} (:db state)]
    (cond
      error {:error {:status 503
                     :message "Main datastore unavailable"
                     :details (.getMessage ^Exception error)}}
      (nil? conn) {:error {:status 503
                           :message "Main datastore unavailable"}}
      :else
      (try
        (let [db (d/db conn)
              basis (:basisT db)
              tx-inst (reduce (fn [acc [inst]]
                                (if (or (nil? acc)
                                        (pos? (.compareTo ^java.util.Date inst acc)))
                                  inst
                                  acc))
                              nil
                              (d/q '[:find ?inst
                                     :where [_ :db/txInstant ?inst]]
                                   db))]
          {:basis-t basis
           :tx-inst-ms (when tx-inst (.getTime ^java.util.Date tx-inst))})
        (catch Exception e
          {:error {:status 500
                   :message "Failed to read datastore snapshot"
                   :details (.getMessage e)}})))))

(defn- datastore-snapshot-handler
  [state]
  (fn [request]
    (if (terminal-admin-authorized? state request)
      (let [snapshot (datastore-snapshot state)]
        (if-let [err (:error snapshot)]
          (common/error-response (:status err) (:message err) (:details err))
          {:status 200
           :body {:datomic snapshot}}))
      (common/error-response 403 "Admin token required"))))

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
    (if-let [restart! (:site/restart! state)]
      (try
        (restart!)
        {:status 202
         :body {:status "restarting"}}
        (catch Exception e
          (log/warn e "Failed to restart site")
          (common/error-response 500 "Unable to restart site.")))
      (common/error-response 500 "Site restart unavailable."))))

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

(defn- terminal-restart
  [cfg base-url]
  (let [token (get-in cfg [:terminal :admin-token])]
    (cond
      (str/blank? (str base-url))
      {:error {:status 400 :message "Terminal service URL missing"}}

      (str/blank? (str token))
      {:error {:status 400 :message "Terminal admin token missing"}}

      :else
      (try
        (let [resp (http/request {:method :post
                                  :url (join-url base-url "/system/restart")
                                  :throw-exceptions false
                                  :socket-timeout 3000
                                  :conn-timeout 3000
                                  :headers {"X-Terminal-Admin-Token" token}
                                  :as :text})
              status (:status resp)]
          (if (<= 200 status 299)
            {:status :ok}
            {:error {:status status
                     :message (str "Terminal restart failed (HTTP " status ")")}}))
        (catch Exception e
          {:error {:status 502
                   :message "Terminal restart failed"
                   :details (.getMessage e)}})))))

(defn- services-handler
  [state]
  (fn [_request]
    (let [cfg (:config state)
          app-url (service-url (get-in cfg [:http :host]) (get-in cfg [:http :port]))
          site-url (service-url (get-in cfg [:site :host]) (get-in cfg [:site :port]))
          terminal-url (get-in cfg [:terminal :base-url])
          canary-url (get-in cfg [:terminal :canary-base-url])
          token (get-in cfg [:terminal :admin-token])
          checked-at (now-ms)
          services [{:id "app"
                     :label "App API"
                     :url app-url
                     :restartable? (boolean (:app/restart! state))
                     :health (assoc (app-health state) :checked-at checked-at)}
                    {:id "site"
                     :label "Public site"
                     :url site-url
                     :restartable? (boolean (:site/restart! state))
                     :health (assoc (site-health site-url) :checked-at checked-at)}
                    {:id "terminal-stable"
                     :label "Terminal (stable)"
                     :url terminal-url
                     :restartable? (and (not (str/blank? (str terminal-url)))
                                        (not (str/blank? (str token))))
                     :health (assoc (http-health terminal-url "/health") :checked-at checked-at)}
                    {:id "terminal-canary"
                     :label "Terminal (canary)"
                     :url canary-url
                     :restartable? (and (not (str/blank? (str canary-url)))
                                        (not (str/blank? (str token))))
                     :health (assoc (http-health canary-url "/health") :checked-at checked-at)}]]
      {:status 200
       :body {:services services}})))

(defn- restart-service-handler
  [state]
  (fn [request]
    (let [service-id (get-in request [:path-params :id])
          cfg (:config state)]
      (case service-id
        "app" ((restart-handler state) request)
        "site" ((site-restart-handler state) request)
        "terminal-stable"
        (let [res (terminal-restart cfg (get-in cfg [:terminal :base-url]))]
          (if-let [err (:error res)]
            (common/error-response (:status err) (:message err) (:details err))
            {:status 202 :body {:status "restarting"}}))
        "terminal-canary"
        (let [res (terminal-restart cfg (get-in cfg [:terminal :canary-base-url]))]
          (if-let [err (:error res)]
            (common/error-response (:status err) (:message err) (:details err))
            {:status 202 :body {:status "restarting"}}))
        (common/error-response 404 "Unknown service")))))

(defn routes
  [state]
  [["/system/datastore/snapshot"
    {:get (datastore-snapshot-handler state)}]
   ["/system"
    {:middleware [common/require-session
                  (common/require-roles #{:role/admin})]}
    ["/restart" {:post (restart-handler state)}]
    ["/site/restart" {:post (site-restart-handler state)}]
    ["/services" {:get (services-handler state)}]
    ["/services/:id/restart" {:post (restart-service-handler state)}]]])
