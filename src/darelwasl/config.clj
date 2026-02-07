(ns darelwasl.config
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private default-config
  {:http {:port 3000
          :host "0.0.0.0"}
   :site {:port 3200
          :host "0.0.0.0"
          :base-path ""
          :logo-file-id "2b643ed0-c402-43d5-b3f9-cdd1ee15ea4b"
          :smtp {:host nil
                 :port 587
                 :starttls? true
                 :user nil
                 :pass nil}
          :mail-from "admin@darelwasl.com"
          :lead-email-to "admin@darelwasl.com"
          :enabled? true}
   :telegram {:webhook-enabled? false
              :commands-enabled? true
              :notifications-enabled? false
              :http-timeout-ms 3000
              :link-token-ttl-ms 900000
              :polling-enabled? false
              :polling-interval-ms 2000
              :auto-bind-username nil
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
   :github {:api-url "https://api.github.com"
            :timeout-ms 3000
            :repo-owner nil
            :repo-name nil
            :token nil
            :prs-per-page 20
            :commits-per-pr 10}
   :files {:storage-dir "data/files"}
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

(defn- read-secret-file
  [path]
  (when (and path (not (str/blank? path)))
    (let [file (io/file path)]
      (when (.exists file)
        (some-> (slurp file) str/trim (not-empty))))))

(defn- normalize-token
  [value]
  (let [raw (some-> value str/trim)]
    (when (and raw (not (str/blank? raw)) (not= "x-oauth-basic" raw))
      raw)))

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
        (assoc-in [:site :base-path]
                  (env-str (get env "SITE_BASE_PATH")
                           (get-in default-config [:site :base-path])))
        (assoc-in [:site :logo-file-id]
                  (env-str (get env "SITE_LOGO_FILE_ID")
                           (get-in default-config [:site :logo-file-id])))
        (assoc-in [:site :smtp :host]
                  (env-str (get env "SITE_SMTP_HOST")
                           (get-in default-config [:site :smtp :host])))
        (assoc-in [:site :smtp :port]
                  (parse-int (get env "SITE_SMTP_PORT")
                             (get-in default-config [:site :smtp :port])))
        (assoc-in [:site :smtp :starttls?]
                  (env-bool (get env "SITE_SMTP_STARTTLS")
                            (get-in default-config [:site :smtp :starttls?])))
        (assoc-in [:site :smtp :user]
                  (env-str (get env "SITE_SMTP_USER")
                           (get-in default-config [:site :smtp :user])))
        (assoc-in [:site :smtp :pass]
                  (env-str (get env "SITE_SMTP_PASS")
                           (get-in default-config [:site :smtp :pass])))
        (assoc-in [:site :mail-from]
                  (env-str (get env "SITE_MAIL_FROM")
                           (get-in default-config [:site :mail-from])))
        (assoc-in [:site :lead-email-to]
                  (env-str (get env "SITE_LEAD_EMAIL_TO")
                           (get-in default-config [:site :lead-email-to])))
        (assoc-in [:site :enabled?]
                  (env-bool (get env "SITE_ENABLED")
                            (get-in default-config [:site :enabled?])))
        (assoc :telegram
               (let [raw-profile (some-> (get env "TELEGRAM_PROFILE") str/trim)
                     profile (when-not (str/blank? raw-profile) (str/lower-case raw-profile))
                     prefix (if (= profile "dev") "TELEGRAM_DEV_" "TELEGRAM_")
                     envk (fn [suffix] (get env (str prefix suffix)))]
                 {:profile (keyword (or profile "prod"))
                  :bot-token (env-str (envk "BOT_TOKEN") nil)
                  :webhook-secret (env-str (envk "WEBHOOK_SECRET") nil)
                  :webhook-base-url (env-str (envk "WEBHOOK_BASE_URL") nil)
                  :webhook-enabled? (env-bool (envk "WEBHOOK_ENABLED")
                                              (get-in default-config [:telegram :webhook-enabled?]))
                  :commands-enabled? (env-bool (envk "COMMANDS_ENABLED")
                                               (get-in default-config [:telegram :commands-enabled?]))
                  :notifications-enabled? (env-bool (envk "NOTIFICATIONS_ENABLED")
                                                    (get-in default-config [:telegram :notifications-enabled?]))
                  :http-timeout-ms (parse-int (envk "HTTP_TIMEOUT_MS")
                                              (get-in default-config [:telegram :http-timeout-ms]))
                  :link-token-ttl-ms (parse-int (envk "LINK_TOKEN_TTL_MS")
                                                (get-in default-config [:telegram :link-token-ttl-ms]))
                  :polling-enabled? (env-bool (envk "POLLING_ENABLED")
                                              (get-in default-config [:telegram :polling-enabled?]))
                  :polling-interval-ms (parse-int (envk "POLLING_INTERVAL_MS")
                                                  (get-in default-config [:telegram :polling-interval-ms]))
                  :auto-bind-username (env-str (envk "AUTO_BIND_USERNAME")
                                               (get-in default-config [:telegram :auto-bind-username]))
                  :auto-set-webhook? (env-bool (envk "AUTO_SET_WEBHOOK")
                                               (get-in default-config [:telegram :auto-set-webhook?]))}))
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
        (assoc :github
               (let [default-token (get-in default-config [:github :token])
                     token-env (normalize-token (get env "GITHUB_TOKEN"))
                     token-file (env-str (get env "GITHUB_TOKEN_FILE") ".secrets/github_token")
                     token-file-value (normalize-token (read-secret-file token-file))
                     token (or token-env token-file-value default-token)]
                 {:api-url (env-str (get env "GITHUB_API_URL")
                                    (get-in default-config [:github :api-url]))
                  :timeout-ms (parse-int (get env "GITHUB_TIMEOUT_MS")
                                         (get-in default-config [:github :timeout-ms]))
                  :repo-owner (env-str (get env "GITHUB_REPO_OWNER")
                                       (get-in default-config [:github :repo-owner]))
                  :repo-name (env-str (get env "GITHUB_REPO_NAME")
                                      (get-in default-config [:github :repo-name]))
                  :token token
                  :prs-per-page (parse-int (get env "GITHUB_PRS_PER_PAGE")
                                           (get-in default-config [:github :prs-per-page]))
                  :commits-per-pr (parse-int (get env "GITHUB_COMMITS_PER_PR")
                                             (get-in default-config [:github :commits-per-pr]))}))
        (assoc :files
               {:storage-dir (env-str (get env "FILES_STORAGE_DIR")
                                      (get-in default-config [:files :storage-dir]))})
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
