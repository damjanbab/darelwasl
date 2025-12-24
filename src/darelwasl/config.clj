(ns darelwasl.config
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private default-config
  {:http {:port 3000
          :host "0.0.0.0"}
   :site {:port 3200
          :host "0.0.0.0"}
   :telegram {:webhook-enabled? false
              :commands-enabled? true
              :notifications-enabled? false
              :http-timeout-ms 3000
              :link-token-ttl-ms 900000
              :auto-set-webhook? true}
   :rezultati {:base-url "https://m.rezultati.com"
               :http-timeout-ms 3000
               :cache-ttl-ms 30000}
   :supersport {:base-url "https://www.supersport.hr"
                :http-timeout-ms 5000}
   :betting {:reference-books ["pinnacle" "betfair" "sbobet" "bet365" "unibet"]
             :fallback-books ["bet365" "unibet"]
             :execution-book "supersport.hr"
             :close-offset-minutes 10
             :event-horizon-hours 72
             :scheduler-enabled? true
             :scheduler-poll-ms 60000}
   :terminal {:host "127.0.0.1"
              :port 4010
              :base-url "http://127.0.0.1:4010"
              :admin-token nil
              :data-dir "data/terminal"
              :work-dir "data/terminal/sessions"
              :logs-dir "data/terminal/logs"
              :repo-url "https://github.com/damjanbab/darelwasl.git"
              :tmux-prefix "codex"
              :codex-command "codex"
              :port-range-start 4100
              :port-range-end 4199
              :auto-start-app? true
              :auto-start-site? false
              :poll-ms 1000
              :max-output-bytes 20000}
   :outbox {:worker-enabled? false
            :poll-ms 1000}
   :datomic {:storage-dir "data/datomic"
             :system "darelwasl"
             :db-name "darelwasl"}
   :fixtures {:auto-seed? true}})

(defn- parse-int
  [value default]
  (try
    (Integer/parseInt value)
    (catch Exception _
      default)))

(defn- env-str
  [env-value default]
  (if (or (nil? env-value) (str/blank? env-value))
    default
    env-value))

(defn- env-bool
  [env-value default]
  (if (or (nil? env-value) (str/blank? env-value))
    default
    (let [lower (str/lower-case env-value)]
      (contains? #{"1" "true" "yes" "y" "on"} lower))))

(defn- env-csv
  [env-value default]
  (if (or (nil? env-value) (str/blank? env-value))
    default
    (->> (str/split env-value #",")
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn- normalize-storage-dir
  [env-value default]
  (let [raw (env-str env-value default)]
    (cond
      (nil? raw) nil
      (= ":mem" (str/lower-case raw)) :mem
      :else (.getAbsolutePath (io/file raw)))))

(defn load-config
  "Load configuration from environment with sensible defaults for local dev."
  []
  (let [env (System/getenv)]
    (-> default-config
        (assoc-in [:http :port]
                  (parse-int (get env "APP_PORT")
                             (get-in default-config [:http :port])))
        (assoc-in [:http :host]
                  (env-str (get env "APP_HOST")
                           (get-in default-config [:http :host])))
        (assoc-in [:site :port]
                  (parse-int (get env "SITE_PORT")
                             (get-in default-config [:site :port])))
        (assoc-in [:site :host]
                  (env-str (get env "SITE_HOST")
                           (get-in default-config [:site :host])))
        (assoc :telegram
               {:bot-token (env-str (get env "TELEGRAM_BOT_TOKEN") nil)
                :webhook-secret (env-str (get env "TELEGRAM_WEBHOOK_SECRET") nil)
                :webhook-base-url (env-str (get env "TELEGRAM_WEBHOOK_BASE_URL") nil)
                :webhook-enabled? (env-bool (get env "TELEGRAM_WEBHOOK_ENABLED")
                                            (get-in default-config [:telegram :webhook-enabled?]))
                :commands-enabled? (env-bool (get env "TELEGRAM_COMMANDS_ENABLED")
                                             (get-in default-config [:telegram :commands-enabled?]))
                :notifications-enabled? (env-bool (get env "TELEGRAM_NOTIFICATIONS_ENABLED")
                                                  (get-in default-config [:telegram :notifications-enabled?]))
                :http-timeout-ms (parse-int (get env "TELEGRAM_HTTP_TIMEOUT_MS")
                                            (get-in default-config [:telegram :http-timeout-ms]))
                :link-token-ttl-ms (parse-int (get env "TELEGRAM_LINK_TOKEN_TTL_MS")
                                              (get-in default-config [:telegram :link-token-ttl-ms]))
                :auto-set-webhook? (env-bool (get env "TELEGRAM_AUTO_SET_WEBHOOK")
                                             (get-in default-config [:telegram :auto-set-webhook?]))})
        (assoc :rezultati
               {:base-url (env-str (get env "REZULTATI_BASE_URL")
                                   (get-in default-config [:rezultati :base-url]))
                :http-timeout-ms (parse-int (get env "REZULTATI_TIMEOUT_MS")
                                            (get-in default-config [:rezultati :http-timeout-ms]))
                :cache-ttl-ms (parse-int (get env "REZULTATI_CACHE_TTL_MS")
                                         (get-in default-config [:rezultati :cache-ttl-ms]))})
        (assoc :supersport
               {:base-url (env-str (get env "SUPERSPORT_BASE_URL")
                                   (get-in default-config [:supersport :base-url]))
                :http-timeout-ms (parse-int (get env "SUPERSPORT_TIMEOUT_MS")
                                            (get-in default-config [:supersport :http-timeout-ms]))})
        (assoc :betting
               {:reference-books (env-csv (get env "BETTING_REFERENCE_BOOKS")
                                          (get-in default-config [:betting :reference-books]))
                :fallback-books (env-csv (get env "BETTING_FALLBACK_BOOKS")
                                         (get-in default-config [:betting :fallback-books]))
                :execution-book (env-str (get env "BETTING_EXECUTION_BOOK")
                                         (get-in default-config [:betting :execution-book]))
                :close-offset-minutes (parse-int (get env "BETTING_CLOSE_OFFSET_MINUTES")
                                                 (get-in default-config [:betting :close-offset-minutes]))
                :event-horizon-hours (parse-int (get env "BETTING_EVENT_HORIZON_HOURS")
                                                (get-in default-config [:betting :event-horizon-hours]))
                :scheduler-enabled? (env-bool (get env "BETTING_SCHEDULER_ENABLED")
                                              (get-in default-config [:betting :scheduler-enabled?]))
                :scheduler-poll-ms (parse-int (get env "BETTING_SCHEDULER_POLL_MS")
                                              (get-in default-config [:betting :scheduler-poll-ms]))})
        (assoc :terminal
               (let [host (env-str (get env "TERMINAL_HOST")
                                   (get-in default-config [:terminal :host]))
                     port (parse-int (get env "TERMINAL_PORT")
                                     (get-in default-config [:terminal :port]))
                     base-url (env-str (get env "TERMINAL_API_URL")
                                       (str "http://" host ":" port))]
                 {:host host
                 :port port
                 :base-url base-url
                 :admin-token (env-str (get env "TERMINAL_ADMIN_TOKEN") nil)
                 :data-dir (env-str (get env "TERMINAL_DATA_DIR")
                                     (get-in default-config [:terminal :data-dir]))
                  :work-dir (env-str (get env "TERMINAL_WORK_DIR")
                                     (get-in default-config [:terminal :work-dir]))
                  :logs-dir (env-str (get env "TERMINAL_LOG_DIR")
                                     (get-in default-config [:terminal :logs-dir]))
                  :repo-url (env-str (get env "TERMINAL_REPO_URL")
                                     (get-in default-config [:terminal :repo-url]))
                  :tmux-prefix (env-str (get env "TERMINAL_TMUX_PREFIX")
                                        (get-in default-config [:terminal :tmux-prefix]))
                  :codex-command (env-str (get env "TERMINAL_CODEX_CMD")
                                          (get-in default-config [:terminal :codex-command]))
                 :port-range-start (parse-int (get env "TERMINAL_PORT_RANGE_START")
                                               (get-in default-config [:terminal :port-range-start]))
                 :port-range-end (parse-int (get env "TERMINAL_PORT_RANGE_END")
                                             (get-in default-config [:terminal :port-range-end]))
                 :auto-start-app? (env-bool (get env "TERMINAL_AUTO_START_APP")
                                            (get-in default-config [:terminal :auto-start-app?]))
                 :auto-start-site? (env-bool (get env "TERMINAL_AUTO_START_SITE")
                                             (get-in default-config [:terminal :auto-start-site?]))
                 :poll-ms (parse-int (get env "TERMINAL_POLL_MS")
                                      (get-in default-config [:terminal :poll-ms]))
                 :max-output-bytes (parse-int (get env "TERMINAL_MAX_OUTPUT_BYTES")
                                               (get-in default-config [:terminal :max-output-bytes]))}))
        (assoc :outbox
               {:worker-enabled? (env-bool (get env "OUTBOX_WORKER_ENABLED")
                                           (get-in default-config [:outbox :worker-enabled?]))
                :poll-ms (parse-int (get env "OUTBOX_WORKER_POLL_MS")
                                    (get-in default-config [:outbox :poll-ms]))})
        (assoc-in [:datomic :storage-dir]
                  (normalize-storage-dir (get env "DATOMIC_STORAGE_DIR")
                                         (get-in default-config [:datomic :storage-dir])))
        (assoc-in [:datomic :system]
                  (env-str (get env "DATOMIC_SYSTEM")
                           (get-in default-config [:datomic :system])))
        (assoc-in [:datomic :db-name]
                  (env-str (get env "DATOMIC_DB_NAME")
                           (get-in default-config [:datomic :db-name])))
        (assoc :fixtures
               {:auto-seed? (env-bool (get env "ALLOW_FIXTURE_SEED")
                                      (get-in default-config [:fixtures :auto-seed?]))}))))
