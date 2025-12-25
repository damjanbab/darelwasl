(ns darelwasl.workers.telegram-poller
  (:require [clojure.tools.logging :as log]
            [darelwasl.telegram :as telegram]))

(def default-poll-ms 2000)

(defn- poll-once!
  [state offset]
  (let [cfg (get-in state [:config :telegram])
        res (telegram/get-updates! cfg {:offset offset
                                        :limit 100})]
    (if-let [err (:error res)]
      (do
        (log/warn "Telegram polling error" {:error err :details (:details res)})
        offset)
      (do
        (doseq [update (:updates res)]
          (let [result (telegram/handle-update state update)]
            (when-let [err (:error result)]
              (log/warn "Telegram update failed" {:error err}))
            (when (= :ignored (:status result))
              (log/debug "Telegram update ignored" {:reason (:reason result)}))))
        (or (:next-offset res) offset)))))

(defn run-loop!
  [state & [{:keys [poll-ms]}]]
  (let [interval (or poll-ms default-poll-ms)]
    (log/info "Telegram polling started" {:interval-ms interval})
    (loop [offset nil]
      (let [next-offset (poll-once! state offset)]
        (Thread/sleep interval)
        (recur next-offset)))))

(defn start!
  [state]
  (future
    (try
      (run-loop! state {:poll-ms (get-in state [:config :telegram :polling-interval-ms])})
      (catch InterruptedException _
        (log/info "Telegram polling stopped"))
      (catch Exception e
        (log/error e "Telegram polling crashed")))))
