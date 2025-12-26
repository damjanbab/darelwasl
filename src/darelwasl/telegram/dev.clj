(ns darelwasl.telegram.dev
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [darelwasl.config :as config]
            [darelwasl.db :as db]
            [darelwasl.telegram :as telegram]))

(defn- cfg
  []
  (config/load-config))

(defn- telegram-cfg
  []
  (get-in (cfg) [:telegram]))

(defn- conn!
  []
  (let [state (db/connect! (get-in (cfg) [:datomic]))]
    (or (:conn state)
        (throw (ex-info "Database not ready" {:error (:error state)})))))

(defn- usage
  []
  (println "Telegram dev CLI")
  (println "")
  (println "Commands:")
  (println "  get-updates [offset]")
  (println "  send-message <chat-id> <text>")
  (println "  bind-chat-id <username> <chat-id>")
  (println "  link-token <username>")
  (println "")
  (println "Env:")
  (println "  TELEGRAM_BOT_TOKEN is required for Telegram API calls")
  (println "  TELEGRAM_HTTP_TIMEOUT_MS optional (default 3000)")
  (println "  TELEGRAM_LINK_TOKEN_TTL_MS optional (default 900000)")
  (println ""))

(defn- api-url
  [token path]
  (str "https://api.telegram.org/bot" token "/" path))

(defn- http-get-json
  [url {:keys [timeout-ms]}]
  (let [resp (http/get url {:as :text
                            :throw-exceptions false
                            :socket-timeout timeout-ms
                            :conn-timeout timeout-ms})]
    (cond
      (<= 200 (:status resp) 299) (json/read-str (:body resp) :key-fn keyword)
      :else (throw (ex-info "Telegram HTTP error"
                            {:status (:status resp)
                             :body (:body resp)})))))

(defn get-updates!
  ([offset] (get-updates! offset {}))
  ([offset {:keys [timeout-ms]}]
   (let [{:keys [bot-token http-timeout-ms]} (telegram-cfg)
         timeout-ms (or timeout-ms http-timeout-ms 3000)]
     (when (str/blank? bot-token)
       (throw (ex-info "TELEGRAM_BOT_TOKEN not set" {})))
     (let [url (cond-> (api-url bot-token "getUpdates")
                 (some? offset) (str "?offset=" (long offset)))
           body (http-get-json url {:timeout-ms timeout-ms})
           updates (:result body)]
       (println (format "ok=%s updates=%s" (:ok body) (count updates)))
       (doseq [u updates]
         (let [update-id (:update_id u)
               chat-id (or (get-in u [:message :chat :id])
                           (get-in u [:edited_message :chat :id]))
               text (or (get-in u [:message :text])
                        (get-in u [:edited_message :text]))]
           (println (format "update_id=%s chat_id=%s text=%s"
                            update-id
                            chat-id
                            (pr-str text)))))
       {:updates updates}))))

(defn send-message-cli!
  [chat-id text]
  (let [cfg (telegram-cfg)
        res (telegram/send-message! cfg {:chat-id (str chat-id)
                                         :text text
                                         :message-key (str "dev-" (System/currentTimeMillis))})]
    (if-let [err (:error res)]
      (do
        (println "error:" err)
        (System/exit 1))
      (println "sent:" (:telegram/message-id res)))))

(defn bind-chat-id!
  [username chat-id]
  (let [conn (conn!)]
    (db/transact! conn {:tx-data [[:db/add [:user/username username] :user/telegram-chat-id (str chat-id)]]})
    (println "bound chat id" chat-id "to user" username)))

(defn link-token!
  [username]
  (let [conn (conn!)
        db (d/db conn)
        user-id (ffirst (d/q '[:find ?id
                               :in $ ?u
                               :where [?e :user/username ?u]
                                      [?e :user/id ?id]]
                             db username))]
    (when-not user-id
      (println "error: user not found:" username)
      (System/exit 1))
    (let [res (telegram/ensure-link-token! {:db {:conn conn}
                                            :config {:telegram (telegram-cfg)}}
                                           user-id)]
      (if-let [err (:error res)]
        (do (println "error:" err) (System/exit 1))
        (println "token:" (:token res))))))

(defn -main
  [& args]
  (let [[cmd & rest] args]
    (case cmd
      nil (do (usage) (shutdown-agents) (System/exit 0))
      "help" (do (usage) (shutdown-agents) (System/exit 0))
      "--help" (do (usage) (shutdown-agents) (System/exit 0))
      "get-updates" (let [offset (some-> (first rest) str/trim (Long/parseLong))]
                      (get-updates! offset)
                      (shutdown-agents))
      "send-message" (let [[chat-id & words] rest]
                       (when (or (str/blank? chat-id) (empty? words))
                         (usage)
                         (System/exit 1))
                       (send-message-cli! chat-id (str/join " " words))
                       (shutdown-agents))
      "bind-chat-id" (let [[username chat-id] rest]
                       (when (or (str/blank? username) (str/blank? chat-id))
                         (usage)
                         (System/exit 1))
                       (bind-chat-id! username chat-id)
                       (shutdown-agents))
      "link-token" (let [[username] rest]
                     (when (str/blank? username)
                       (usage)
                       (System/exit 1))
                     (link-token! username)
                     (shutdown-agents))
      (do
        (usage)
        (shutdown-agents)
        (System/exit 1)))))
