(ns darelwasl.telegram
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private default-timeout-ms 3000)

(def ^:private allowed-commands
  #{:start :help :tasks :task :stop})

(defn webhook-enabled?
  [cfg]
  (true? (:webhook-enabled? cfg)))

(defn commands-enabled?
  [cfg]
  (true? (:commands-enabled? cfg)))

(defn- bot-url
  [cfg path]
  (when-let [token (:bot-token cfg)]
    (str "https://api.telegram.org/bot" token "/" path)))

(defn- request-json
  [cfg path payload]
  (if (str/blank? (:bot-token cfg))
    {:error "Telegram bot token not configured"}
    (let [timeout (:http-timeout-ms cfg default-timeout-ms)]
      (try
        (let [resp (http/post (bot-url cfg path)
                              {:headers {"Content-Type" "application/json"}
                               :body (json/write-str payload)
                               :socket-timeout timeout
                               :conn-timeout timeout
                               :as :json})]
          (if (true? (get-in resp [:body :ok]))
            {:result (get-in resp [:body :result])}
            {:error (or (get-in resp [:body :description])
                        "Telegram API error")
             :details (select-keys (:body resp) [:description :error_code])}))
        (catch Exception e
          (log/warn e "Telegram API request failed" {:path path})
          {:error "Telegram API request failed"})))))

(defn send-message!
  "Send a Telegram message to chat-id. Returns {:telegram/message-id ...} or {:error ...}."
  [cfg {:keys [chat-id text parse-mode message-key]}]
  (cond
    (str/blank? chat-id) {:error "Missing chat id"}
    (str/blank? text) {:error "Missing message text"}
    (str/blank? message-key) {:error "Missing message key for idempotency"}
    :else
    (let [payload (cond-> {:chat_id chat-id
                           :text text}
                    parse-mode (assoc :parse_mode (name parse-mode)))
          resp (request-json cfg "sendMessage" payload)]
      (if-let [err (:error resp)]
        resp
        {:telegram/message-id (get-in resp [:result :message_id])}))))

(defn set-webhook!
  [cfg {:keys [webhook-url secret-token]}]
  (cond
    (str/blank? webhook-url) {:error "Missing webhook URL"}
    (str/blank? secret-token) {:error "Missing webhook secret token"}
    :else
    (request-json cfg "setWebhook" {:url webhook-url
                                    :secret_token secret-token
                                    :allowed_updates ["message"]})))

(defn- parse-command
  [text]
  (when (and text (str/starts-with? text "/"))
    (let [[cmd & args] (-> text str/trim (str/split #"\s+"))
          cmd (some-> cmd (str/replace #"^/" "") keyword)]
      (when (allowed-commands cmd)
        {:command cmd
         :args args}))))

(defn- extract-update
  [update]
  (let [message (or (:message update) (get update "message"))
        update-id (or (:update_id update) (get update "update_id"))
        chat-id (or (get-in message [:chat :id])
                    (get-in message ["chat" "id"]))
        text (or (:text message) (get message "text"))
        {:keys [command args]} (parse-command text)]
    {:update-id update-id
     :chat-id (some-> chat-id str)
     :text text
     :command command
     :args args}))

(defn- reply-text
  [{:keys [command args]}]
  (case command
    :help "Commands: /start <link-token>, /help, /tasks, /task <id>, /stop. Note: chat linking required; notifications are gated by feature flags."
    :start (str "Link token received: " (first args) ". Chat linking is not yet enabled on this endpoint. Please try again after linking is available.")
    :tasks "Task listing via Telegram is not enabled yet. Use the web app for now."
    :task "Task lookup via Telegram is not enabled yet. Use the web app for details."
    :stop "Chat unlink is not enabled yet. If you need to stop messages, disable notifications in the app."
    "Unknown command. Send /help for available commands."))

(defn handle-update
  "Process a Telegram update payload. Returns {:status ...} or {:error ...}."
  [state update]
  (let [cfg (get-in state [:config :telegram])]
    (cond
      (not (webhook-enabled? cfg)) {:status :ignored :reason :webhook-disabled}
      (not (commands-enabled? cfg)) {:status :ignored :reason :commands-disabled}
      :else
      (let [{:keys [update-id chat-id command] :as parsed} (extract-update update)]
        (cond
          (nil? update-id) {:error "Missing update id"}
          (str/blank? chat-id) {:error "Missing chat id"}
          (nil? command) {:status :ignored :reason :unsupported-command}
          :else
          (let [reply (reply-text parsed)
                send-res (send-message! cfg {:chat-id chat-id
                                             :text reply
                                             :message-key (str "update-" update-id)})]
            (if (:error send-res)
              send-res
              {:status :handled
               :telegram/command command
               :telegram/message-id (:telegram/message-id send-res)})))))))
