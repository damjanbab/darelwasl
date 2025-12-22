(ns darelwasl.workers.betting-scheduler
  (:require [clojure.tools.logging :as log]
            [datomic.client.api :as d]
            [darelwasl.betting :as betting])
  (:import (java.util Date)))

(def default-poll-ms 60000)
(def default-horizon-hours 72)
(def default-close-offset-minutes 10)

(defn- now-inst
  []
  (Date.))

(defn- horizon-inst
  [^Date now hours]
  (Date. (+ (.getTime now) (* 60 60 1000 (long hours)))))

(defn- close-at
  [^Date commence offset-minutes]
  (Date. (- (.getTime commence) (* 60 1000 (long offset-minutes)))))

(defn- close-captured?
  [db event-id]
  (boolean
   (d/q '[:find ?q .
          :in $ ?eid
          :where [?e :betting.event/id ?eid]
                 [?q :betting.quote/event ?e]
                 [?q :betting.quote/close? true]]
        db event-id)))

(defn- upcoming-events
  [db now horizon]
  (let [rows (d/q '[:find ?eid ?commence
                    :where [?e :betting.event/id ?eid]
                           [?e :betting.event/commence-time ?commence]]
                  db)
        now-ms (.getTime now)
        horizon-ms (.getTime horizon)]
    (->> rows
         (keep (fn [[eid commence]]
                 (when (and (instance? Date commence)
                            (<= now-ms (.getTime commence))
                            (<= (.getTime commence) horizon-ms))
                   {:event-id eid
                    :commence commence})))
         vec)))

(defn- poll-once!
  [{:keys [config db]}]
  (let [conn (:conn db)
        cfg (:betting config)
        now (now-inst)
        horizon (horizon-inst now (or (:event-horizon-hours cfg) default-horizon-hours))
        offset (or (:close-offset-minutes cfg) default-close-offset-minutes)
        events (upcoming-events (d/db conn) now horizon)]
    (doseq [{:keys [event-id commence]} events]
      (let [capture-at (close-at commence offset)]
        (when (and (<= (.getTime capture-at) (.getTime now))
                   (< (.getTime now) (.getTime commence))
                   (not (close-captured? (d/db conn) event-id)))
          (let [res (betting/capture-close! conn config {:event-id event-id :refresh? true})]
            (if (:error res)
              (log/warn "Betting close capture failed" {:event-id event-id :error (:error res)})
              (log/info "Betting close captured" {:event-id event-id}))))))))

(defn run-loop!
  "Poll upcoming events and capture close snapshots near kickoff."
  [state & [{:keys [poll-ms]}]]
  (let [interval (or poll-ms default-poll-ms)]
    (log/info "Betting close scheduler started" {:interval-ms interval})
    (loop []
      (poll-once! state)
      (Thread/sleep interval)
      (recur))))

(defn start!
  [state]
  (future
    (try
      (run-loop! state {:poll-ms (get-in state [:config :betting :scheduler-poll-ms])})
      (catch InterruptedException _
        (log/info "Betting close scheduler stopped"))
      (catch Exception e
        (log/error e "Betting close scheduler crashed")))))
