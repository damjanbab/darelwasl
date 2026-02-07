(ns darelwasl.workers.outbox
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [darelwasl.bootstrap :as bootstrap]
            [darelwasl.config :as config]
            [darelwasl.email :as email]
            [darelwasl.outbox :as outbox]
            [darelwasl.telegram :as telegram])
  (:import (java.util UUID)))

(def default-poll-ms 1000)
(def default-max-attempts 5)

(defn- worker-id [] (str (UUID/randomUUID)))

(defn- deliver-telegram
  [state entry payload]
  (let [cfg (get-in state [:config :telegram])
        res (telegram/send-message! cfg payload)]
    (if (:error res)
      {:error (:error res)}
      {:ok true})))

(defn- deliver-email
  [state _entry payload]
  (let [site-cfg (get-in state [:config :site])
        res (email/send-smtp! site-cfg payload)]
    (if (:error res)
      {:error (:error res)}
      {:ok true})))

(defn- deliver
  [state entry]
  (let [payload (outbox/decode-payload (:outbox/payload entry))]
    (case (:outbox/integration entry)
      :integration/telegram (deliver-telegram state entry payload)
      :integration/email (deliver-email state entry payload)
      {:error "Unsupported integration"})))

(defn- process-once!
  [state worker-id]
  (let [conn (get-in state [:db :conn])]
    (when-let [entry (or (outbox/claim-one! conn worker-id :integration/email)
                         (outbox/claim-one! conn worker-id :integration/telegram))]
      (let [res (deliver state entry)]
        (if (:error res)
          (do
            (log/warn "Outbox delivery failed" {:outbox/id (:outbox/id entry) :error (:error res)})
            (outbox/mark-failure! conn (:outbox/id entry) (:error res) (:outbox/attempts entry) default-max-attempts))
          (do
            (log/info "Outbox delivered" {:outbox/id (:outbox/id entry) :integration (:outbox/integration entry)})
            (outbox/mark-success! conn (:outbox/id entry)))))
      :processed)))

(defn run-loop!
  "Poll outbox and deliver messages. Blocks forever."
  [state & [{:keys [poll-ms]}]]
  (let [worker (worker-id)
        interval (or poll-ms default-poll-ms)]
    (log/info "Outbox worker started" {:worker worker :interval-ms interval})
    (loop []
      (when-not (.isInterrupted (Thread/currentThread))
        (let [res (try
                    (process-once! state worker)
                    (catch InterruptedException _
                      ::stop)
                    (catch Exception e
                      (log/error e "Outbox loop error")
                      nil))]
          (when (nil? res)
            (try
              (Thread/sleep interval)
              (catch InterruptedException _
                nil)))
          (when-not (= res ::stop)
            (recur)))))))

(defn -main
  [& _args]
  (log/info "Starting outbox worker")
  (let [cfg (config/load-config)
        db-state (bootstrap/initialize-db! cfg {:context "outbox-worker"})]
    (when (:error db-state)
      (log/error (:error db-state) "DB not ready; worker exiting")
      (System/exit 1))
    (run-loop! {:config cfg
                :db db-state})))
