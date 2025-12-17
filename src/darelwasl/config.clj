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
              :link-token-ttl-ms 900000}
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
                                              (get-in default-config [:telegram :link-token-ttl-ms]))})
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
